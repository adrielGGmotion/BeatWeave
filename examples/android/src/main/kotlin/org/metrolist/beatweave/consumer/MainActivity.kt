package org.metrolist.beatweave.consumer

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "Running local library checks…" }
        setContentView(status)
        Thread {
                val message =
                    try {
                        SmokeChecks.run(this)
                    } catch (error: Throwable) {
                        "FAIL: " + android.util.Log.getStackTraceString(error)
                    }
                android.util.Log.i("BeatWeaveCheck", message)
                runOnUiThread { status.text = message }
            }
            .start()
    }
}
