package hk.tq9.ui

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** 輸入法本身唔可以要權限，所以借一個透明 activity 去問 */
class MicPermissionActivity : AppCompatActivity() {

    private val ask = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(
            this,
            if (granted) "已開啟咪高峰，返去再撳一次 🎤" else "冇咪高峰權限，用唔到語音輸入",
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ask.launch(Manifest.permission.RECORD_AUDIO)
    }
}
