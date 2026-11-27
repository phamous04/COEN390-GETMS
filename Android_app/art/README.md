# Guide artwork

SVG sources of the pictures shown in the exercise flow. Each one has a VectorDrawable of the same
name in `app/src/main/res/drawable/`.

| File | Shown in | Picture |
|---|---|---|
| `placement_emg.svg` | Placement guide, page 1 | Front of the upper arm: MyoWare 2.0 on the biceps belly, MID and END electrodes in line with the fibres, REF electrode beside the muscle (MyoWare 2.0 Quick Start Guide) |
| `placement_imu.svg` | Placement guide, page 2 | Outer side of the arm: upper-arm IMU just above the elbow, forearm IMU just below it, both the same way round |
| `placement_strap.svg` | Placement guide, page 3 | Elbow bent: straps flat, cable between the sensors with slack |
| `tutorial_curl_down.svg` | Tutorial, Ready screen | Arm straight, elbow at the side |
| `tutorial_curl_up.svg` | Tutorial, Ready screen | Arm fully curled |

Rules the app relies on (`ui/exercise/ExerciseGuide.kt`, `ArmArt`):

- Draw the **right arm**. The app mirrors the picture for the left arm, so use no text and no
  symbol that reads wrong when flipped.
- Use **one colour** (`#1B2B2B`) and express light and dark areas with `fill-opacity` /
  `stroke-opacity`. The app tints the whole picture with the theme's text colour.
- 240 × 240 viewBox. Keep to shapes, paths and `rotate(...)` groups: no gradients, filters, CSS
  or dashed strokes.
- The two tutorial frames must differ only in the forearm, so the crossfade reads as a movement.

After editing an SVG, convert it again: Android Studio > New > Vector Asset > Local file, with the
same name, into `res/drawable/`.
