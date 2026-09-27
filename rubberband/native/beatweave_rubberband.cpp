/* SPDX-License-Identifier: GPL-2.0-or-later */
#include "include/beatweave_rubberband.h"
#include "vendor/rubberband/rubberband/RubberBandStretcher.h"
#include <algorithm>
#include <cmath>
#include <limits>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

using RubberBand::RubberBandStretcher;
static thread_local std::string last_error;
struct bw_rb_session {
    std::unique_ptr<RubberBandStretcher> rb;
    int channels;
    int64_t expected, target, studied = 0, processed = 0;
    bool study_done = false, process_done = false;
    std::vector<std::vector<float>> planar;
    std::vector<float *> pointers;
    bw_rb_session(int rate, int ch, int64_t source, int64_t output) :
        channels(ch), expected(source), target(output), planar(ch, std::vector<float>(4096)), pointers(ch) {
        const int flags = RubberBandStretcher::OptionProcessOffline |
            RubberBandStretcher::OptionEngineFiner |
            RubberBandStretcher::OptionChannelsTogether |
            RubberBandStretcher::OptionThreadingNever;
        rb.reset(new RubberBandStretcher(rate, ch, flags, double(output) / double(source), 1.0));
        if (rb->getEngineVersion() != 3) throw std::runtime_error("Rubber Band R3 engine required");
        rb->setExpectedInputDuration(static_cast<size_t>(source));
        rb->setMaxProcessSize(4096);
        for (int c = 0; c < ch; ++c) pointers[c] = planar[c].data();
    }
};
static void require_session(bw_rb_session *s) { if (!s) throw std::invalid_argument("Session is closed"); }
static void deinterleave(bw_rb_session *s, const float *in, int n) {
    require_session(s);
    if (n < 0 || n > 4096 || (n && !in)) throw std::invalid_argument("Invalid audio block");
    for (int i = 0; i < n; ++i) for (int c = 0; c < s->channels; ++c) {
        const float x = in[i * s->channels + c];
        if (!std::isfinite(x)) throw std::invalid_argument("Non-finite PCM sample");
        s->planar[c][i] = x;
    }
}
#define BW_TRY try { last_error.clear();
#define BW_FAIL(ret) } catch (const std::exception &e) { last_error = e.what(); return ret; } catch (...) { last_error = "Unknown native audio engine failure"; return ret; }
extern "C" {
bw_rb_session *bw_rb_create(int rate, int channels, int64_t source, int64_t output) {
    BW_TRY
    if (rate < 8000 || rate > 192000 || channels != 2 || source <= 0 || output <= 0 ||
        uint64_t(source) > std::numeric_limits<size_t>::max() || uint64_t(output) > std::numeric_limits<size_t>::max())
        throw std::invalid_argument("Invalid stereo stretch dimensions");
    if (double(output) / source < 0.25 || double(output) / source > 4.0)
        throw std::invalid_argument("Stretch ratio outside supported 0.25..4.0 range");
    return new bw_rb_session(rate, channels, source, output);
    BW_FAIL(nullptr)
}
void bw_rb_destroy(bw_rb_session *s) { delete s; }
int bw_rb_engine_version(bw_rb_session *s) { BW_TRY require_session(s); return s->rb->getEngineVersion(); BW_FAIL(-2) }
int bw_rb_set_keyframes(bw_rb_session *s, const int64_t *source, const int64_t *output, int n) {
    BW_TRY
    require_session(s);
    if (s->studied || s->processed) throw std::logic_error("Keyframes must be configured before study");
    if (n < 2 || !source || !output) throw std::invalid_argument("At least two keyframes required");
    std::map<size_t, size_t> points;
    for (int i = 0; i < n; ++i) {
        if (source[i] < 0 || output[i] < 0 || uint64_t(source[i]) > std::numeric_limits<size_t>::max() ||
            uint64_t(output[i]) > std::numeric_limits<size_t>::max() ||
            (i && (source[i] <= source[i-1] || output[i] <= output[i-1])))
            throw std::invalid_argument("Keyframes must increase strictly in both axes");
        if (i) {
            const double ratio = double(output[i] - output[i-1]) / double(source[i] - source[i-1]);
            if (ratio < 0.25 || ratio > 4.0) throw std::invalid_argument("Local keyframe ratio outside supported 0.25..4.0 range");
        }
        // R3's first-map-point ratio is output/source; including the implicit
        // origin (0,0) causes 0/0 and silently resets the initial ratio to 1.
        // Keep the common schedule's origin for validation, but omit it here.
        if (source[i] != 0) points[static_cast<size_t>(source[i])] = static_cast<size_t>(output[i]);
    }
    if (source[0] != 0 || output[0] != 0 || source[n-1] != s->expected || output[n-1] != s->target)
        throw std::invalid_argument("Keyframes must span the complete input");
    s->rb->setKeyFrameMap(points);
    return 0;
    BW_FAIL(-2)
}
int bw_rb_study(bw_rb_session *s, const float *in, int n, int final_block) {
    BW_TRY
    require_session(s);
    if (s->study_done || s->processed) throw std::logic_error("Study pass already finished");
    if (s->studied + n > s->expected || (final_block && s->studied + n != s->expected))
        throw std::invalid_argument("Study pass length differs from declared source length");
    deinterleave(s, in, n);
    s->rb->study(s->pointers.data(), n, final_block != 0);
    s->studied += n;
    s->study_done = final_block != 0;
    return 0;
    BW_FAIL(-2)
}
int bw_rb_process(bw_rb_session *s, const float *in, int n, int final_block) {
    BW_TRY
    require_session(s);
    if (!s->study_done || s->process_done) throw std::logic_error("Process needs completed study and open process pass");
    if (s->processed + n > s->expected || (final_block && s->processed + n != s->expected))
        throw std::invalid_argument("Process pass length differs from declared source length");
    deinterleave(s, in, n);
    s->rb->process(s->pointers.data(), n, final_block != 0);
    s->processed += n;
    s->process_done = final_block != 0;
    return 0;
    BW_FAIL(-2)
}
int bw_rb_available(bw_rb_session *s) { BW_TRY require_session(s); return s->rb->available(); BW_FAIL(-2) }
int bw_rb_retrieve(bw_rb_session *s, float *out, int n) {
    BW_TRY
    require_session(s);
    if (n < 0 || n > 4096 || (n && !out)) throw std::invalid_argument("Invalid output block");
    const int got = static_cast<int>(s->rb->retrieve(s->pointers.data(), n));
    for (int i = 0; i < got; ++i) for (int c = 0; c < s->channels; ++c) out[i*s->channels+c] = s->planar[c][i];
    return got;
    BW_FAIL(-2)
}
const char *bw_rb_last_error(void) { return last_error.c_str(); }
}
