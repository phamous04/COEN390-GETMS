// GETMS GATT server on NimBLE. Transport only: Control writes are handed to
// the session layer, which owns the device state and calls back in here to
// publish Status, EMG packets and Rep Events.

#include <stdio.h>
#include <string.h>
#include "ble.h"
#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"

static const char *TAG = "ble";

// 6e40000X-0000-4000-8000-00805f9b34fb, bytes reversed (NimBLE is little-endian).
#define GETMS_UUID128(x) \
    BLE_UUID128_INIT(0xfb, 0x34, 0x9b, 0x5f, 0x80, 0x00, 0x00, 0x80, \
                     0x00, 0x40, 0x00, 0x00, (x), 0x00, 0x40, 0x6e)

static const ble_uuid128_t svc_uuid = GETMS_UUID128(0x00);
static const ble_uuid128_t emg_uuid = GETMS_UUID128(0x01);
static const ble_uuid128_t status_uuid = GETMS_UUID128(0x03);
static const ble_uuid128_t control_uuid = GETMS_UUID128(0x04);
static const ble_uuid128_t rep_uuid = GETMS_UUID128(0x05);

// Connection interval request, units of 1.25 ms: 15-30 ms (doc §5.2: <= 30 ms).
#define CONN_ITVL_MIN 12
#define CONN_ITVL_MAX 24
#define SUPERVISION_TIMEOUT 400 // units of 10 ms = 4 s

static ble_callbacks_t s_cb;
static uint8_t s_own_addr_type;
static char s_name[16];

static uint16_t s_emg_handle;
static uint16_t s_status_handle;
static uint16_t s_control_handle;
static uint16_t s_rep_handle;

static volatile uint16_t s_conn = BLE_HS_CONN_HANDLE_NONE;
static volatile bool s_emg_subscribed;
static volatile bool s_status_subscribed;
static volatile bool s_rep_subscribed;
static volatile bool s_streaming;

static uint8_t s_status[GETMS_STATUS_LEN];
static portMUX_TYPE s_status_lock = portMUX_INITIALIZER_UNLOCKED;

static void advertise(void);

// ---- Outgoing ----------------------------------------------------------------

static esp_err_t notify(uint16_t handle, bool subscribed, const uint8_t *data, size_t len)
{
    uint16_t conn = s_conn;
    if (!subscribed || conn == BLE_HS_CONN_HANDLE_NONE) {
        return ESP_ERR_INVALID_STATE;
    }
    struct os_mbuf *om = ble_hs_mbuf_from_flat(data, len);
    if (om == NULL) {
        return ESP_ERR_NO_MEM;
    }
    // Takes ownership of om, also on failure.
    int rc = ble_gatts_notify_custom(conn, handle, om);
    return rc == 0 ? ESP_OK : (rc == BLE_HS_ENOMEM ? ESP_ERR_NO_MEM : ESP_FAIL);
}

void ble_set_status(const uint8_t *status)
{
    taskENTER_CRITICAL(&s_status_lock);
    memcpy(s_status, status, GETMS_STATUS_LEN);
    taskEXIT_CRITICAL(&s_status_lock);
    notify(s_status_handle, s_status_subscribed, status, GETMS_STATUS_LEN);
}

void ble_set_streaming(bool on)
{
    s_streaming = on;
}

uint16_t ble_mtu(void)
{
    uint16_t conn = s_conn;
    return conn == BLE_HS_CONN_HANDLE_NONE ? 0 : ble_att_mtu(conn);
}

esp_err_t ble_send_emg(uint16_t seq, uint32_t t0_us, const int16_t *ch_a)
{
    if (!s_streaming) {
        return ESP_ERR_INVALID_STATE;
    }
    uint8_t buf[GETMS_EMG_PACKET_LEN];
    uint8_t *p = buf;
    *p++ = seq & 0xff;
    *p++ = seq >> 8;
    for (int i = 0; i < 4; i++) {
        *p++ = (t0_us >> (8 * i)) & 0xff;
    }
    for (int i = 0; i < GETMS_EMG_SAMPLES_PER_PACKET; i++) {
        uint16_t a = (uint16_t)ch_a[i];
        *p++ = a & 0xff;
        *p++ = a >> 8;
        *p++ = 0; // chB: no second sensor yet
        *p++ = 0;
    }
    return notify(s_emg_handle, s_emg_subscribed, buf, sizeof(buf));
}

esp_err_t ble_send_rep(const uint8_t *event)
{
    return notify(s_rep_handle, s_rep_subscribed, event, GETMS_REP_EVENT_LEN);
}

// ---- GATT -------------------------------------------------------------------

static int chr_access(uint16_t conn, uint16_t attr, struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    if (attr == s_status_handle && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t buf[GETMS_STATUS_LEN];
        taskENTER_CRITICAL(&s_status_lock);
        memcpy(buf, s_status, sizeof(buf));
        taskEXIT_CRITICAL(&s_status_lock);
        return os_mbuf_append(ctxt->om, buf, sizeof(buf)) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (attr == s_control_handle && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint8_t buf[GETMS_CMD_MAX_LEN] = {0};
        uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
        os_mbuf_copydata(ctxt->om, 0, len < sizeof(buf) ? len : sizeof(buf), buf);
        // The session validates it; a bad command ends in the ERROR state.
        s_cb.on_command(buf, len);
        return (len == 0 || len > GETMS_CMD_MAX_LEN) ? BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN : 0;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

static const struct ble_gatt_svc_def gatt_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &svc_uuid.u,
        .characteristics = (struct ble_gatt_chr_def[]){
            {
                .uuid = &emg_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_emg_handle,
            },
            {
                .uuid = &status_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_status_handle,
            },
            {
                .uuid = &control_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_WRITE,
                .val_handle = &s_control_handle,
            },
            {
                .uuid = &rep_uuid.u,
                .access_cb = chr_access,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .val_handle = &s_rep_handle,
            },
            {0},
        },
    },
    {0},
};

// ---- GAP --------------------------------------------------------------------

static int gap_event(struct ble_gap_event *event, void *arg)
{
    struct ble_gap_conn_desc desc;

    switch (event->type) {
    case BLE_GAP_EVENT_CONNECT:
        if (event->connect.status != 0) {
            ESP_LOGW(TAG, "Connection failed (status %d)", event->connect.status);
            advertise();
            return 0;
        }
        s_conn = event->connect.conn_handle;
        if (ble_gap_conn_find(s_conn, &desc) == 0) {
            const uint8_t *a = desc.peer_id_addr.val;
            ESP_LOGI(TAG, "Connected: %02X:%02X:%02X:%02X:%02X:%02X", a[5], a[4], a[3], a[2], a[1], a[0]);
        }
        // The phone requests the MTU (doc §5.2: 247); we ask for a short
        // connection interval so 50 packets/s fit comfortably.
        struct ble_gap_upd_params params = {
            .itvl_min = CONN_ITVL_MIN,
            .itvl_max = CONN_ITVL_MAX,
            .latency = 0,
            .supervision_timeout = SUPERVISION_TIMEOUT,
        };
        ble_gap_update_params(s_conn, &params);
        s_cb.on_connection(true);
        return 0;

    case BLE_GAP_EVENT_DISCONNECT:
        ESP_LOGI(TAG, "Disconnected (reason 0x%03x)", event->disconnect.reason);
        // Recording carries on; the app recovers missed reps with GET_REPS.
        s_conn = BLE_HS_CONN_HANDLE_NONE;
        s_emg_subscribed = s_status_subscribed = s_rep_subscribed = false;
        s_cb.on_connection(false);
        advertise();
        return 0;

    case BLE_GAP_EVENT_SUBSCRIBE: {
        uint16_t h = event->subscribe.attr_handle;
        bool on = event->subscribe.cur_notify;
        if (h == s_emg_handle) {
            s_emg_subscribed = on;
        } else if (h == s_status_handle) {
            s_status_subscribed = on;
        } else if (h == s_rep_handle) {
            s_rep_subscribed = on;
        }
        ESP_LOGI(TAG, "Notifications %s on handle %u", on ? "on" : "off", h);
        return 0;
    }

    case BLE_GAP_EVENT_MTU:
        ESP_LOGI(TAG, "MTU %u", event->mtu.value);
        return 0;

    case BLE_GAP_EVENT_CONN_UPDATE:
        if (event->conn_update.status == 0 && ble_gap_conn_find(event->conn_update.conn_handle, &desc) == 0) {
            ESP_LOGI(TAG, "Connection interval %u.%02u ms", desc.conn_itvl * 125 / 100, desc.conn_itvl * 125 % 100);
        }
        return 0;

    case BLE_GAP_EVENT_ADV_COMPLETE:
        advertise();
        return 0;

    default:
        return 0;
    }
}

static void advertise(void)
{
    // Advertising packet: flags + service UUID (so the app can filter scans).
    struct ble_hs_adv_fields fields = {0};
    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.uuids128 = &svc_uuid;
    fields.num_uuids128 = 1;
    fields.uuids128_is_complete = 1;
    int rc = ble_gap_adv_set_fields(&fields);
    if (rc != 0) {
        printf("ERROR: BLE advertising data failed (%d)\n", rc);
        return;
    }

    // Scan response: the name (does not fit next to a 128-bit UUID in 31 B).
    struct ble_hs_adv_fields rsp = {0};
    rsp.name = (const uint8_t *)s_name;
    rsp.name_len = strlen(s_name);
    rsp.name_is_complete = 1;
    rc = ble_gap_adv_rsp_set_fields(&rsp);
    if (rc != 0) {
        printf("ERROR: BLE scan response failed (%d)\n", rc);
        return;
    }

    struct ble_gap_adv_params params = {
        .conn_mode = BLE_GAP_CONN_MODE_UND,
        .disc_mode = BLE_GAP_DISC_MODE_GEN,
    };
    rc = ble_gap_adv_start(s_own_addr_type, NULL, BLE_HS_FOREVER, &params, gap_event, NULL);
    if (rc != 0) {
        printf("ERROR: BLE advertising failed (%d)\n", rc);
        return;
    }
    ESP_LOGI(TAG, "Advertising as %s", s_name);
}

static void on_sync(void)
{
    ble_hs_util_ensure_addr(0);
    ble_hs_id_infer_auto(0, &s_own_addr_type);

    uint8_t addr[6] = {0};
    ble_hs_id_copy_addr(s_own_addr_type, addr, NULL);
    snprintf(s_name, sizeof(s_name), "GETMS-%02X%02X", addr[1], addr[0]);
    ble_svc_gap_device_name_set(s_name);

    advertise();
}

static void on_reset(int reason)
{
    printf("ERROR: BLE stack reset (%d)\n", reason);
}

static void host_task(void *arg)
{
    nimble_port_run(); // returns only after nimble_port_stop()
    nimble_port_freertos_deinit();
}

esp_err_t ble_init(const ble_callbacks_t *callbacks)
{
    s_cb = *callbacks;

    esp_err_t err = nimble_port_init();
    if (err != ESP_OK) {
        return err;
    }

    ble_hs_cfg.sync_cb = on_sync;
    ble_hs_cfg.reset_cb = on_reset;

    ble_svc_gap_init();
    ble_svc_gatt_init();
    if (ble_gatts_count_cfg(gatt_svcs) != 0 || ble_gatts_add_svcs(gatt_svcs) != 0) {
        return ESP_FAIL;
    }

    nimble_port_freertos_init(host_task);
    return ESP_OK;
}
