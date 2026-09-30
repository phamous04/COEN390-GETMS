CLI commands for the issues
These commands change a shared repo. gh issue edit --body replaces the whole issue body, so review the script before running it. You need GitHub CLI (gh) signed in with write access (gh auth status). Run the setup blocks (2.1 to 2.4) first, all in the same shell session, so the variables and functions from 2.1 are defined for the later blocks.

2.1 Setup and helper functions
R=phamous04/COEN390-GETMS

mkbody() { # story docref points deps ac...
  local s="$1" d="$2" p="$3" dep="$4"; shift 4
  printf '%s\n\n**Design doc:** %s\n**Estimate:** %s pts\n**Depends on:** %s\n\n### Acceptance criteria\n' "$s" "$d" "$p" "$dep"
  for a in "$@"; do printf -- '- [ ] %s\n' "$a"; done
  printf '\n### Definition of done\n- [ ] Code reviewed and merged\n- [ ] Tests added and passing in CI\n- [ ] Design doc updated if behaviour changed\n'
}
upd() { local n="$1" t="$2"; shift 2
  gh issue edit "$n" -R "$R" --title "$t" --body "$(mkbody "$@")" >/dev/null && echo "updated #$n"; }
link() { gh api -X POST "repos/$R/issues/$1/sub_issues" -F sub_issue_id="$(gh api "repos/$R/issues/$2" --jq .id)" >/dev/null; }
new() { local parent="$1" t="$2"; shift 2
  local url; url=$(gh issue create -R "$R" --title "$t" --label story --body "$(mkbody "$@")")
  local n=${url##*/}; link "$parent" "$n"; echo "created #$n under #$parent"; }

2.2 Labels, milestones, and issue template
gh label create task --color 0E8A16 --description "Technical task" -R "$R" 2>/dev/null
gh label create docs --color 5319E7 --description "Documentation" -R "$R" 2>/dev/null

for m in "M1 Foundations" "M2 Firmware & BLE" "M3 Session Flow" "M4 Processing & Metrics" \
         "M5 Data Platform" "M6 Dashboards" "M7 Validation & Docs"; do
  gh api "repos/$R/milestones" -f title="$m" >/dev/null && echo "milestone: $m"
done

# Run inside your local clone of COEN390-GETMS
mkdir -p .github/ISSUE_TEMPLATE
cat > .github/ISSUE_TEMPLATE/story.md <<'EOF'
---
name: User story
about: Story belonging to an epic
labels: story
---
As a <role>, I want <capability> so that <benefit>.

**Design doc:** §
**Estimate:** pts
**Depends on:**

### Acceptance criteria
- [ ]

### Definition of done
- [ ] Code reviewed and merged
- [ ] Tests added and passing in CI
- [ ] Design doc updated if behaviour changed
EOF
git add .github/ISSUE_TEMPLATE/story.md "Document/Android App Design Document.md"
git commit -m "Design doc v2 and story issue template" && git push

2.3 Fix the meta issues
gh issue edit 1 -R "$R" --title "Architecture — Review and approve design doc v2" \
  --remove-label "good first issue" --add-label docs \
  --body "Review Document/Android App Design Document.md v2 as a team. Close when all members approve and open questions in §14 are resolved or deferred."
gh issue edit 93 -R "$R" --body "- [x] Acceptance criteria added to every story
- [x] Estimates added
- [ ] Team review of estimates
- [ ] Break stories of 8 pts or more into tasks"

2.4 Update existing stories
# Epic 1 — Hardware
upd 9  "1.1 — Select and Prepare Hardware Components" "As the team, we want a finalized bill of materials so that parts can be ordered without delay." "§3.1" 2 "—" "BOM committed (part, qty, source, cost)" "EMG provides 2 channels (anterior + posterior deltoid)" "Power decision recorded (§3.3)"
upd 10 "1.2 — Assemble the ESP32 and Sensor System" "As the team, we want the boards wired together so that firmware work can start." "§3.2" 3 "#9" "Wiring diagram and pin map committed" "EMG outputs on ADC1 pins only"
upd 11 "1.3 — Integrate the MPU-6050 IMU" "As the team, we want the IMU read reliably so that motion can be measured." "§3.1, §4" 2 "#10" "Device detected at 0x68, I2C 400 kHz" "100 Hz reads for 60 s with zero I2C errors"
upd 12 "1.4 — Integrate the EMG Sensor" "As the team, we want both EMG channels read so that muscle activity can be measured." "§3.1, §3.2" 3 "#10" "Both channels sampled at 1 kHz on ADC1" "Visible burst on contraction; rest noise documented" "Analog conditioning documented"
upd 13 "1.5 — Design the Power and Charging Setup" "As the team, we want a power decision so that the enclosure can be finalized." "§3.3" 2 "#9" "Decision recorded in design doc" "If battery: meets NFR-6"
upd 14 "1.6 — Build the Wearable Sensor Mount" "As a patient, I want a comfortable arm mount so that I can exercise normally." "§3.4" 3 "#10, #13" "Enclosure holds board, IMU, optional battery" "IMU x-axis marked, aligned with humerus"
upd 15 "1.7 — Implement Quick Arm-Swap Mechanism" "As a clinician, I want to move the device between arms quickly so that sessions stay short." "§3.4, §12.1" 3 "#14" "Swap in 60 s or less by one person" "Electrode landmark photos committed"
upd 16 "1.8 — Verify Hardware Connections" "As the team, we want verified wiring so that faults are not blamed on firmware." "§3.2" 1 "#10" "Continuity checklist completed" "No brownout with BLE and sampling active"
upd 17 "1.9 — Perform Initial Hardware Testing" "As the team, we want sample recordings so that algorithm work can start early." "§4" 2 "#11, #12" "10-min soak test, no resets" "Sample arm-raise data committed to repo"

# Epic 2 — Firmware
upd 19 "2.1 — Acquire EMG Signals" "As a clinician, I want muscle activity captured precisely so that activation and fatigue metrics are valid." "§4" 3 "#12" "2 channels at 1000 Hz from hardware timer" "Sample jitter under 50 us"
upd 20 "2.2 — Acquire IMU Motion Data" "As a clinician, I want arm motion captured so that ROM and speed can be measured." "§4" 2 "#11" "Accel 4 g, gyro 500 dps, 100 Hz" "Gyro bias estimated at boot"
upd 21 "2.3 — Filter Raw EMG On-Device" "As a clinician, I want clean EMG so that metrics are not driven by noise." "§4, §2.1" 3 "#19" "20-450 Hz band-pass and 60 Hz notch biquads" "Filtered RAW samples streamed, not the envelope"
upd 22 "2.4 — Estimate Orientation Using Sensor Fusion" "As a clinician, I want an orientation estimate so that joint angle can be computed." "§4" 5 "#20" "Madgwick 6-DOF outputs unit quaternion at 100 Hz" "Static drift under 2 deg/min"
upd 23 "2.5 — Run Sensor Processing Concurrently" "As a patient, I want no gaps in my data so that my results are accurate." "§4" 3 "#19, #20" "sampleTask, dspTask, bleTask with queues" "No sequence gaps over 10 min"
upd 24 "2.6 — Timestamp Sensor Samples" "As a clinician, I want EMG and IMU aligned so that activation timing is correct." "§4, §5.3" 2 "#23" "Each packet has seq and t0_us" "Rollover handled"
upd 25 "2.7 — Manage Device Power" "As a patient, I want the device to last a full session so that it does not die mid-exercise." "§3.3, §4" 2 "#13" "Idle state only advertises" "Battery service exposed if battery used"

# Epic 3 — BLE
upd 27 "3.1 — Advertise and Discover the Sensor Device" "As a patient, I want the app to find my sensor so that I can start a session." "§5.2" 2 "#23" "Firmware advertises GETMS-XXXX with service UUID" "App scans filtered by service UUID and lists RSSI"
upd 28 "3.2 — Establish a BLE Connection" "As a patient, I want a reliable connection so that data is not lost." "§5.2" 3 "#27" "Kable connection" "MTU 247 requested, high priority, interval 30 ms or less"
upd 29 "3.3 — Stream EMG and IMU Data" "As a clinician, I want complete data streams so that metrics are valid." "§5.3" 5 "#28, #24" "Packets parsed into Kotlin Flows" "Sequence gaps counted; loss 1 percent or less (NFR-2)"
upd 30 "3.4 — Receive Device Status" "As a patient, I want to see device state so that I know it is working." "§5.1" 2 "#28" "State, error flags, fw version, battery shown in UI"
upd 31 "3.5 — Handle BLE Disconnection and Reconnection" "As a patient, I want the app to recover from drops so that I do not restart the session." "§6.3" 3 "#28" "Auto-reconnect with backoff" "Recording marked Interrupted if gap over 1 s"
upd 32 "3.6 — Keep BLE Connection Across Arm Switch" "As a patient, I want the sensor to stay usable while switching arms. Scope: BLE layer only; UI flow is 4.8." "§6.3" 2 "#31" "Reconnect within 10 s (NFR-5)" "STOP sent before switch, START after recalibration"

# Epic 4 — App and session
upd 34 "4.1 — Authenticate Users" "As a user, I want to sign in so that my data is private." "§6.4, §10.1" 3 "10.2" "Email and password via Firebase Auth" "Routes by role" "Role cannot be self-assigned"
upd 35 "4.2 — Select an Exercise" "As a patient, I want to choose my prescribed exercise so that the right parameters are used." "§6.4, §10.3" 2 "#67" "List from exercises collection with prescribed reps"
upd 36 "4.3 — Select the Starting Limb" "As a clinician, I want the first limb to alternate so that order effects do not bias results." "§12.1, FR-8" 2 "#68" "Proposes opposite of last session" "Override requires a reason, stored on session"
upd 37 "4.4 — Calibrate the Current Limb (Rest, MVC, Neutral Pose)" "As a patient, I want guided calibration so that my measurements are normalized." "§6.4, §7" 5 "#29" "5 s rest gives mean and SD per channel" "3 x 3 s MVC gives peak" "3 s neutral pose gives q0" "Stored in recording.calibration"
upd 38 "4.5 — Guide the Exercise Session" "As a patient, I want clear guidance so that I perform reps correctly." "§6.3" 3 "#37" "3-2-1 countdown" "Auto-stop at prescribed reps or manual stop"
upd 39 "4.6 — Display Live Exercise Feedback" "As a patient, I want live feedback so that I stay motivated." "§6.4, FR-6" 3 "#49" "Rep counter and live angle trace" "Update latency 300 ms or less (NFR-1)"
upd 40 "4.7 — Complete a Limb Recording" "As a patient, I want my recording saved reliably so that no work is lost." "§6.5" 3 "5.8" "Raw trace saved locally" "Feature pipeline runs" "Recording doc written"
upd 41 "4.8 — Guide the Arm-Switch Process" "As a patient, I want clear arm-switch instructions so that placement is consistent." "§6.4, §12.1" 2 "#32" "Landmark photos shown" "Fixed rest countdown from exercise.restPeriodS" "Recalibration required before second recording"
upd 42 "4.9 — Pair Both Recordings into One Session" "As a clinician, I want both limbs grouped so that asymmetry can be computed." "§10.4" 2 "#40" "Session status complete when both limbs exist" "Asymmetry computed via 6.11"

# Epic 5 — Signal processing
upd 44 "5.1 — Calculate Limb Joint Angle" "As a clinician, I want shoulder flexion angle so that ROM is measurable." "§7.1" 5 "#22, #37" "q_rel = inv(q0) * q" "Static poses 0/45/90 deg within 5 deg"
upd 45 "5.2 — Filter the Joint-Angle Signal" "As a clinician, I want smooth angle data so that derived metrics are stable." "§7.1" 2 "#44" "4th-order Butterworth 6 Hz" "Causal for live, zero-phase for features"
upd 46 "5.3 — Calculate Angular Velocity and Acceleration" "As a clinician, I want speed and acceleration signals so that movement quality is measurable." "§7.1" 2 "#45" "Central differences in deg/s and deg/s2"
upd 47 "5.4 — Compute EMG Envelope and Normalize to %MVC" "As a clinician, I want normalized activation so that sessions are comparable." "§7.2" 3 "#21, #37" "Rectify plus 100 ms RMS" "Divide by MVC peak x 100"
upd 48 "5.5 — Detect Muscle Activation Onset" "As a clinician, I want onset times so that activation timing is measurable." "§7.2" 2 "#47" "Envelope over mean plus 3 SD for 50 ms or more" "Synthetic test within 10 ms"
upd 49 "5.6 — Detect Repetitions" "As a patient, I want reps counted automatically so that I do not have to count." "§7.3" 5 "#45" "Prominence and spacing from exercise doc" "95 percent accuracy on labelled data (9.4)"
upd 50 "5.7 — Associate EMG Activity with Repetitions" "As a clinician, I want EMG per rep so that per-rep metrics are possible." "§7.3" 2 "#48, #49" "EMG windows aligned to rep bounds via timestamps"

# Epic 6 — Metrics
upd 52 "6.1 — Calculate Range of Motion" "As a clinician, I want ROM so that I can track mobility." "§8.1" 1 "#49" "max minus min per rep in deg" "Recording value = mean over reps"
upd 53 "6.2 — Calculate Movement Speed" "As a clinician, I want peak speed so that I can track movement vigor." "§8.1" 1 "#46" "Peak abs angular velocity in deg/s"
upd 54 "6.3 — Calculate Movement Acceleration" "As a clinician, I want peak acceleration so that I can assess control." "§8.1" 1 "#46" "Peak abs angular acceleration in deg/s2"
upd 55 "6.4 — Calculate Movement Smoothness (SPARC)" "As a clinician, I want smoothness so that I can assess motor control." "§8.1" 5 "#46" "SPARC on speed profile per rep" "Unit test matches reference values"
upd 56 "6.5 — Calculate Repetition Performance" "As a patient, I want to see reps done and time taken so that I see progress." "§8.1" 1 "#49" "Reps completed over prescribed" "Time to complete in s"
upd 57 "6.6 — Calculate Movement Variability" "As a clinician, I want consistency so that I can judge stability." "§8.1" 1 "#52, #53" "CV of ROM and peak speed"
upd 58 "6.7 — Calculate Muscle Activation" "As a clinician, I want activation level so that I can track strength." "§8.1" 1 "#47, #50" "Mean %MVC of Ch A over activation window"
upd 59 "6.8 — Calculate Activation Timing" "As a clinician, I want onset latency so that I can assess neuromuscular timing." "§8.1" 2 "#48" "EMG onset minus movement onset in ms"
upd 60 "6.9 — Calculate Muscle Fatigue" "As a clinician, I want a fatigue indicator so that I can adjust load." "§8.1" 5 "#50" "MDF per rep, Welch 256 Hann on raw EMG" "Slope across reps in Hz per rep" "Synthetic sinusoid test"
upd 61 "6.10 — Calculate Muscle Coordination" "As a clinician, I want co-contraction so that I can assess coordination." "§8.1" 2 "#47" "CCI = 2 x sum min(A,B) / sum (A+B) x 100"
upd 62 "6.11 — Calculate Bilateral Asymmetry" "As a clinician, I want asymmetry per metric so that I can track recovery." "§8.2" 2 "#42" "AI formula with abs values; 0 if both 0" "Stored on session doc"
upd 63 "6.12 — Compute Asymmetry Trend Series" "As a clinician, I want asymmetry over time. Scope: compute series only; query is 7.9, display is 8.8." "§8.2" 2 "#62, #73" "Series per metric ordered by date"

# Epic 7 — Firebase
upd 65 "7.1 — Store User Accounts" "As a user, I want my profile stored so that the app knows my role and affected side." "§10.1" 2 "10.2" "Fields per schema incl affectedSide" "Role clinician only via console"
upd 66 "7.2 — Store Calibration Data (Embedded in Recording)" "As a clinician, I want calibration kept with each recording so that results are reproducible." "§10.5" 1 "#69" "recording.calibration per schema"
upd 67 "7.3 — Store Exercise Definitions" "As a clinician, I want exercises parameterized so that segmentation adapts." "§10.3" 1 "10.2" "Arm raise seeded with segmentation params and rest period"
upd 68 "7.4 — Store Rehabilitation Sessions" "As a clinician, I want sessions stored so that history is available." "§10.4" 2 "#65" "Fields per schema incl status and order override"
upd 69 "7.5 — Store Limb Recordings" "As a clinician, I want each recording stored so that I can review it." "§10.5" 2 "#68" "userId and clinicianId denormalized" "App and firmware versions stored"
upd 70 "7.6 — Store Extracted Metrics" "As a clinician, I want metrics and per-rep values stored so that I can drill down." "§10.5" 2 "#69" "metrics map plus reps sub-collection"
upd 71 "7.7 — Store Raw Sensor Traces" "As a clinician, I want raw data kept so that I can inspect anomalies." "§10.6" 3 "#69" "Binary format per §10.6" "Local-first, uploaded by WorkManager"
upd 72 "7.8 — Store Session Asymmetry (on Session Doc)" "As a clinician, I want asymmetry stored with the session so that it loads fast." "§10.4" 1 "#62" "session.asymmetry map written"
upd 73 "7.9 — Support Historical Trend Queries" "As a clinician, I want trends to load fast so that reviews are efficient." "§10.5" 2 "#68, #69" "Composite index userId, exerciseId, date" "20 sessions load in 2 s or less"

# Epic 8 — Dashboards
upd 75 "8.1 — Display Patient Progress" "As a patient, I want a progress summary so that I stay motivated." "§9" 3 "#73" "Headline ROM and asymmetry" "Last session summary"
upd 76 "8.2 — Display Patient Trends" "As a patient, I want trend charts so that I see improvement." "§9" 3 "#73" "Vico charts for ROM and ROM asymmetry" "Asymmetry labelled lower is better"
upd 77 "8.3 — Display Rehabilitation Streaks and Personal Bests" "As a patient, I want streaks and bests so that I stay engaged." "§9" 2 "#73" "Streak in consecutive session days" "Best ROM and best speed"
upd 78 "8.4 — Display Clinician Patient List" "As a clinician, I want my patient list so that I can pick who to review." "§9" 2 "7.10" "Only patients linked to me" "Summary per patient"
upd 79 "8.5 — Display Patient Exercise History" "As a clinician, I want session history so that I can navigate sessions." "§9" 2 "#78" "Chronological sessions per exercise"
upd 80 "8.6 — Display Session Metrics" "As a clinician, I want a session metric table so that I see details." "§9" 2 "#79" "Per-limb metrics and per-rep expandable"
upd 81 "8.7 — Display Bilateral Comparison" "As a clinician, I want side-by-side limbs so that asymmetry is obvious." "§9, §8.2" 2 "#80" "Side-by-side bars plus AI percent"
upd 82 "8.8 — Display Metric Trends" "As a clinician, I want any metric over time so that I can evaluate progress." "§9" 3 "#63" "Metric selector" "Asymmetry labelled lower is better"
upd 83 "8.9 — Inspect Raw Signals" "As a clinician, I want raw signals so that I can explain odd metrics." "§9, §10.6" 5 "#71" "Downloaded on demand" "Zoom and pan; rep bounds overlaid"

# Epic 9 — Security and validation
upd 85 "9.1 — Implement Role-Based Access" "As the team, we want rules enforced server-side so that data stays private." "§10.7" 3 "#65" "Firestore and Storage rules deployed"
upd 86 "9.2 — Restrict Patient Data Access" "As a patient, I want only me and my clinician to see my data." "§10.7, NFR-8" 2 "#85" "Emulator tests: cross-patient reads denied"
upd 87 "9.3 — Restrict Clinician Patient Access" "As a patient, I want other clinicians blocked from my data." "§10.7, NFR-8" 2 "#85" "Emulator tests: unlinked clinician denied"
upd 88 "9.4 — Validate Repetition Segmentation" "As the team, we want proof that rep counting works." "§12.2" 3 "#49" "10 or more labelled recordings" "95 percent accuracy or better"
upd 89 "9.5 — Validate Feature Calculations" "As the team, we want metrics verified against known answers." "§12.2" 3 "Epic 6" "Synthetic signal unit test per metric"
upd 90 "9.6 — Validate Measurement Accuracy" "As a clinician, I want ROM validated so that I can trust it." "§12.2, NFR-4" 3 "#52" "30 or more reps vs goniometer" "5 deg RMS or less"
upd 91 "9.7 — Validate BLE Reliability" "As the team, we want BLE reliability measured." "NFR-2, NFR-5" 2 "#29, #32" "Loss 1 percent or less" "20 arm switches, reconnect 10 s or less"
upd 92 "9.8 — Validate System Performance" "As the team, we want performance targets verified." "NFR-1, NFR-3, NFR-6" 2 "#39, 5.8" "Results recorded in validation report"

2.5 Create the missing stories and the new epic
The numbers in the story titles (3.7, 4.10, 10.1, and so on) are the doc's story IDs. GitHub assigns its own issue numbers, which the script prints as each issue is created.
new 18 "2.8 — Handle Control Commands" "As the app, I want to start and stop streaming so that data is only sent while recording." "§4, §5.1" 2 "#28" "START and STOP on Control char" "State reported on Status char"
new 26 "3.7 — Define the BLE GATT Specification" "As the team, we want one shared BLE spec so that firmware and app agree." "§5" 2 "—" "UUIDs generated" "Shared constants in firmware and app"
new 33 "4.10 — Check Signal Quality Before Recording" "As a patient, I want a placement check so that bad data is avoided." "§7.4, FR-4" 3 "#29" "Pass/fail per channel" "Guidance shown on failure"
new 33 "4.11 — Record in a Foreground Service" "As a patient, I want recording to continue if the screen turns off." "§6.5" 3 "#29" "connectedDevice foreground service" "Ongoing notification"
new 33 "4.12 — Handle Bluetooth Permissions" "As a patient, I want clear permission prompts so that I understand why they are needed." "§6.5" 2 "—" "Rationale screen" "Denial handled gracefully"
new 33 "4.13 — Implement Session State Machine" "As the team, we want one state machine so that the flow is predictable." "§6.3" 5 "—" "All states and transitions from §6.3" "Unit tested"
new 33 "4.14 — Settings Screen" "As a user, I want settings so that I can manage device and account." "§6.4" 2 "#34" "Forget device, rest duration, sign out, debug logging"
new 43 "5.8 — Run End-of-Recording Feature Pipeline" "As a clinician, I want metrics ready right after a recording." "§2.1, NFR-3" 3 "Epic 6" "Runs on Dispatchers.Default" "3 s or less on reference phone"
new 64 "7.10 — Link Patients to Clinicians" "As a patient, I want to link to my clinician using an invite code." "§10.1, §10.2, FR-2" 3 "#65" "invites collection" "Rules validate code"
new 64 "7.11 — Offline Caching and Upload Retry" "As a patient, I want no data lost when offline." "§6.5, NFR-7" 3 "#71" "Firestore persistence" "WorkManager retry with backoff"
new 84 "9.9 — Test Dashboard Navigation" "As the team, we want UI tests so that the drill-down does not regress." "§12.2" 3 "Epic 8" "Compose UI tests for full drill-down and session flow"
new 84 "9.10 — Document Health-Data Handling" "As the team, we want a data policy so that test data is handled responsibly." "§11" 1 "—" "Consent, pseudonyms, deletion date documented"

E10=$(gh issue create -R "$R" --title "Epic 10 — Project Setup & Documentation" --label epic \
  --body "Project scaffolding, CI, and documentation deliverables (§13, §15)." | sed 's#.*/##')
new "$E10" "10.1 — Create Android Project Skeleton" "As the team, we want a shared Android project so that work can start in parallel." "§6.1, §6.2" 2 "—" "Compose, Hilt, Navigation, package layout per §6.2"
new "$E10" "10.2 — Set Up Firebase Project" "As the team, we want Firebase configured so that auth and storage work." "§10" 1 "—" "Auth, Firestore, Storage enabled" "Emulator Suite config committed"
new "$E10" "10.3 — Set Up CI" "As the team, we want CI so that broken builds are caught early." "§12" 2 "10.1" "GitHub Actions builds app and runs unit tests on each PR"
new "$E10" "10.4 — Write BLE Interface Specification" "As the team, we want the BLE spec documented for the deliverable." "§5, §15" 1 "3.7" "Spec in Document folder"
new "$E10" "10.5 — Write Algorithm Reference" "As the team, we want algorithms documented for the deliverable." "§7, §8, §15" 2 "Epic 6" "Formulas and parameters documented"
new "$E10" "10.6 — Write Validation Report" "As the team, we want a validation report for the deliverable." "§12, §15" 3 "Epic 9" "Results for all NFRs"

2.6 Assign milestones
ms() { for n in $(gh api "repos/$R/issues/$1/sub_issues" --jq '.[].number') "$1"; do
         gh issue edit "$n" -R "$R" --milestone "$2" >/dev/null; done; echo "$2 <- epic #$1"; }
ms 8  "M1 Foundations"
ms 18 "M2 Firmware & BLE";  ms 26 "M2 Firmware & BLE"
ms 33 "M3 Session Flow"
ms 43 "M4 Processing & Metrics"; ms 51 "M4 Processing & Metrics"
ms 64 "M5 Data Platform"
ms 74 "M6 Dashboards"
ms 84 "M7 Validation & Docs"; ms "$E10" "M7 Validation & Docs"
# Then move 10.1–10.3 to M1 (use the numbers printed above):
# gh issue edit <10.1#> <10.2#> <10.3#> -R "$R" --milestone "M1 Foundations"

2.7 Optional: project board
gh auth refresh -s project
gh project create --owner phamous04 --title "GETMS Board"
# Replace <N> with the project number printed above:
gh issue list -R "$R" --state open --limit 200 --json url --jq '.[].url' | \
  xargs -I{} gh project item-add <N> --owner phamous04 --url {}

Before running:

$E10 must be set. If you run 2.6 in a new shell, first set it to the Epic 10 number printed in 2.5: E10=<number>.
Sub-issue linking can fail. It uses GitHub's REST sub-issues API. If link errors on your gh version, link those issues by hand in the web UI.
The estimates are only a starting point. Review them as a team, as #93 already plans.
