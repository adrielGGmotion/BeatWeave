# ONNX Runtime JNI constructs and inspects these classes by exact binary names.
# Upstream guidance: https://onnxruntime.ai/docs/build/android.html
# Do not reduce this to native methods: TensorInfo, NodeInfo and OrtException
# are also looked up from native code and would otherwise be renamed or removed.
-keep class ai.onnxruntime.** { *; }
