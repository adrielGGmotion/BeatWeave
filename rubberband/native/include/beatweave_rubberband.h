/* SPDX-License-Identifier: GPL-2.0-or-later */
#ifndef BEATWEAVE_RUBBERBAND_H
#define BEATWEAVE_RUBBERBAND_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct bw_rb_session bw_rb_session;
/* Offline R3, stereo-linked, pitch scale 1.0. NULL / negative result means error.
 * Calls on one session must be serialized. Audio buffers are interleaved.
 * Study the complete input, then process the identical input; drain after each
 * process call. Block sizes are bounded to 4096 frames by this adapter. */
bw_rb_session *bw_rb_create(int sample_rate, int channels, int64_t source_frames, int64_t output_frames);
void bw_rb_destroy(bw_rb_session *session);
int bw_rb_engine_version(bw_rb_session *session);
int bw_rb_set_keyframes(bw_rb_session *session, const int64_t *source, const int64_t *output, int count);
int bw_rb_study(bw_rb_session *session, const float *interleaved, int frames, int final_block);
int bw_rb_process(bw_rb_session *session, const float *interleaved, int frames, int final_block);
int bw_rb_available(bw_rb_session *session);
int bw_rb_retrieve(bw_rb_session *session, float *interleaved, int max_frames);
const char *bw_rb_last_error(void);
#ifdef __cplusplus
}
#endif
#endif
