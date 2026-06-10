Gradle will automatically download sherpa-onnx Android AAR files here.

Expected runtime classes:

- `com.k2fsa.sherpa.onnx.OfflineRecognizer`
- `com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig`

Recommended ASR model for this MVP:

- Paraformer zh small
- The model is downloaded during Gradle build and packed into APK assets.
- The app copies it to private app storage on first launch.
