package org.metrolist.beatweave.consumer

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

class SmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val results = Bundle()
        try {
            results.putString("stream", SmokeChecks.run(targetContext))
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", android.util.Log.getStackTraceString(error))
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
