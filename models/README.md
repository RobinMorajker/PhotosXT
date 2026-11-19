# Eraser models

The object eraser (spec §4.10) downloads one of three inpainting models on first use: LaMa, MI-GAN or DeepFill v2. LaMa and MI-GAN come from Hugging Face, pinned in `core/ml` (`EraserModel`). DeepFill v2 had no LiteRT build anywhere, so it is built here and the app downloads it from this repository on GitHub, pinned to the commit that added it and to its SHA-256.

## deepfill_v2_512_fp16.tflite

- **What:** DeepFill v2, "Free-Form Image Inpainting with Gated Convolution" (Jiahui Yu, Zhe Lin, Jimei Yang, Xiaohui Shen, Xin Lu, Thomas Huang; ICCV 2019), the official Places2 (scenes) release from [JiahuiYu/generative_inpainting](https://github.com/JiahuiYu/generative_inpainting).
- **Licence:** CC BY-NC 4.0, the licence of the original code and models: non-commercial use only. Changes made: the generator rebuilt in TensorFlow 2 with LiteRT-friendly attention, fixed at 512 × 512, weights stored as float16. PhotoXT can use it while it is private and non-commercial; it has to go before any commercial release.
- **Interface** (the same as LaMa's file): `image` float32 [1, 3, 512, 512], RGB in 0..1; `mask` float32 [1, 1, 512, 512], 1 = fill. Output float32 [1, 3, 512, 512], RGB in 0..1, the input kept outside the mask. Find the inputs by shape: the mask comes first.
- **Size and checksum:** 8,698,084 bytes, SHA-256 `5561b5aede282dc06762d4da569c98587c82b5622e8b94612ca28d855bedef28`.
- **How it was made:** `python3 deepfill_v2.py` (TensorFlow 2.21, ai-edge-litert). It reads the weights from a tf2onnx export of the official TF graph on Hugging Face (pinned by commit and SHA-256), rebuilds the network, and stops unless the rebuild reproduces the official Places2 example outputs within 1 level out of 255 (it does: mean difference 0.19 and 0.21) and the converted file matches the rebuild within 1 level (mean 0.002, at most 0.41).
- **Speed:** about 3 s on two cloud CPU cores, against 3.7 s for LaMa and 0.4 s for MI-GAN at the same size.
