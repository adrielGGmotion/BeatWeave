package org.metrolist.beatweave.learned

/**
 * Pinned local models with the same frontend and original 1500-frame context. A model identity
 * includes both its training checkpoint and exported graph: cached analyses must not mix them.
 * Larger models can improve estimates but do not certify a musical bar interpretation.
 */
enum class BeatThisModelVariant(
    val assetFileName: String,
    val sha256: String,
    val checkpointSha256: String,
    private val detectorName: String,
) {
    SMALL0(
        "beat-this-small0.onnx",
        "b6d54bca156b039593b6d9d48fd3ab3e5d09be06bbbe2706f0376c9f103d5191",
        "6074be2c4d490c5f6101fcc374a1ec72ae93456e23bb6019783b849f5dc7d47b",
        "beat-this-small0",
    ),
    FINAL0(
        "beat-this-final0.onnx",
        "c8293b7b787e73b40ad5e899624dbe08763268031543ef7ebcab53f8e78da66f",
        "8c328b45f59d8dd3dff219253ff6a8d6482be57d0133a29140e2febbf8eb8331",
        "beat-this-final0",
    );

    val modelId: String
        get() = "$detectorName/$checkpointSha256/onnx-$sha256"
}
