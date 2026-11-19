# 2026-11-19 — 013 — Eraser: LaMa, MI-GAN and DeepFill v2 with a switch

- **Tool:** Claude (Cowork), cloud session linked to robin-laptop. Built and tested in the cloud workspace (Linux, JDK 21, Android SDK 37) on `main` at f470cb7, the repo brought over as a git bundle through the Cowork device bridge.
- **Goal:** Robin asked: "In Phase7 add LaMa (Large Mask Inpainting), MI-GAN and DeepFill v2. with switching option."

## Decisions (made, for Robin to confirm)
- **The switch** sits at the top of the eraser panel, as chips (LaMa · MI-GAN · DeepFill v2), so models can be compared on the same strokes without leaving the editor. The choice is kept in the settings (`EditorSettings.eraserModel`, synced); MI-GAN stays the default. Settings keeps *Delete downloaded models*. Each model downloads when it is first picked and *Download* is tapped.
- **LaMa** is big-lama, the scene model (Places2, Apache-2.0), from `Dineshsoudagar/big-lama-512-LiteRT` on Hugging Face (104 MB), pinned by commit and SHA-256 like MI-GAN. It is a personal account, as the dropped LaMa's was.
- **DeepFill v2** had no LiteRT build anywhere, so I built one (`models/`): the official Places2 network rebuilt in TF2 from the weights in an ONNX export of the official graph, its attention rewritten as matrix multiplies, converted with float16 weights (8.7 MB). The app downloads it from this GitHub repository at the commit that added it (89c3daf), checked by SHA-256. Until that commit is pushed, DeepFill's download fails with the usual "Couldn't download" message.
- **DeepFill v2's licence is CC BY-NC 4.0** (the official code and models): non-commercial use only. PhotoXT can use it while it is private and non-commercial; it has to go before any commercial release. LaMa (Apache-2.0) and MI-GAN (MIT) have no such limit.
- All three models work at 512 px, the size MI-GAN already used. DeepFill v2 was trained at 256 px with holes up to 128 px; at 256 it ran 7× faster but filled worse on both test photos, so it runs at 512 like the others.

## Built
- `core/ml`: `EraserModel` has LAMA, MIGAN and DEEPFILL, each with its pinned URL, size and SHA-256. LaMa and DeepFill share one interface (RGB planes in 0..1 with the hole blanked, plus a mask plane; inputs told apart by their channel count), MI-GAN keeps its own; the conversions sit in `Planes`. Model files are named by their checksum, so a file from an older pin (such as the face model's) is deleted at the next download check. The log says whether a model runs on the GPU or the CPU.
- `feature/editor`: the chips in the eraser panel (`ChipRow` can now be switched off, which it is while a model downloads or runs); the download prompt names the picked model and its size; erasing uses the picked model.
- `core/datastore`: `EditorSettings.eraserModel` is back; an unknown name reads as MI-GAN.
- `models/`: `deepfill_v2.py` (rebuild, conversion and checks), the model file and a README with its source, licence, interface and checksum. `.gitattributes` marks `*.tflite` as binary.
- Tests: `EraserTest` (every model pinned to a commit and a SHA-256; the planes each model reads and writes), and `EraserModelsTest` (core/ml androidTest, for the phone: each model fills a hole over a red sign in a made-up sky with the sky's colour, with times logged).

## How the models were checked (cloud CPU, LiteRT 2.x in Python)
- **LaMa is a scene model this time:** on a road sign against the sky (a photo from Wikimedia Commons) it continued the clouds and power lines, with no face; on a person in a crowded beach scene it continued the boat and the sand, the best of the three there.
- **DeepFill v2 rebuild:** reproduces the official Places2 example outputs (the repository's `case1` and `case4`) within 1 level out of 255 (mean 0.19 and 0.21). The ONNX export itself, run with its missing op supplied, was 15–18 levels off inside the holes and visibly smeared: it rounds the network's nearest-neighbour resizes down, where TensorFlow rounds to nearest; with its resizes set to round to nearest it matched the official outputs within 1 level too. The float16 file matches the rebuild within 0.41 levels, and a second conversion gave the same bytes.
- **Side by side** (sign and beach): all three erase the sign cleanly; on the beach MI-GAN paints new people in, DeepFill v2 copies patches of the surrounding crowd, LaMa does best.
- **Cost at 512 px, two cloud cores:** MI-GAN 0.4 s, DeepFill v2 3 s, LaMa 3.7 s; peak memory of the process 430, 735 and 880 MB.

## Checks run
- The CI command plus the phone check's build, `spotlessCheck detekt lintDebug testDebugUnitTest assembleDebug :baselineprofile:assemble :core:ml:assembleDebugAndroidTest`: green, 232 tests (230 before), no Lint or detekt findings. ktlint wanted a blank line before a multi-line `when` branch.
- `models/deepfill_v2.py` run twice: both checks passed and both runs wrote the same file, byte for byte.

## Not done
- **No phone check.** The session's safety check refused to run the WPSC issuer (twice: reading its folder, then running it), so neither git on Windows nor adb was reachable; I deleted the credential copies staged for it. Still to run on the A15: `EraserModelsTest`, then the eraser in the editor with each model. The risks are Play services' LiteRT running the new files (they use only common builtin ops), the GPU delegate on LaMa's 2,500-odd operations, and time and memory on the A15. DeepFill needs the push first.

## Committing
- Two commits made in the cloud clone (89c3daf adds `models/`, the next one the code and these notes), brought to the laptop as a git bundle through the device bridge and fast-forwarded onto `main` with the device shell's git, once Robin allowed it to delete git's lock files. Robin pushes; DeepFill's download works from then on.

## Problems met
- Maven Central answered 429 again; the init script pointing Gradle and Robolectric at Google's mirror fixed it, as before.
- The full CI took 23 minutes in the cloud: the Gradle daemon's 3 GB heap sat full for much of the Lint and dexing, with 6 GB of swap in use.
- The device bridge dropped for a while mid-session and came back by itself.
