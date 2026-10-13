# YuNet Face Detection Model Card

- Model ID: `yunet-face-detection`
- Version: `2023mar-fp32`
- File: `face_detection_yunet_2023mar.onnx`
- Source repository: `https://github.com/opencv/opencv_zoo`
- Source commit: `47534e27c9851bb1128ccc0102f1145e27f23f98`
- Source path: `models/face_detection_yunet/face_detection_yunet_2023mar.onnx`
- SHA-256: `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`
- Model license: MIT; see `LICENSE` in this directory.
- Runtime: ONNX Runtime Android 1.22.0, CPU execution provider.
- Review date: 2026-10-13
- Commercial/legal status: engineering review complete; formal commercial release remains pending legal review.

## Contract

Input `input` is FP32 `[1, 3, 640, 640]`, NCHW BGR with values in `[0,255]`.
Outputs are `cls_{8,16,32}`, `obj_{8,16,32}`, `bbox_{8,16,32}` and
`kps_{8,16,32}`. The application reports a face box, confidence and five
landmarks: right eye, left eye, nose tip, right mouth corner and left mouth
corner.

## Intended use and limitations

The model is used only to locate faces and landmarks in user-selected images.
It does not identify people, store embeddings or infer sensitive attributes.
Small, occluded, rotated or poorly lit faces may be missed. Model confidence
is not a calibrated probability. Independent LocalVision quality and device
performance acceptance is still required before a production release.

