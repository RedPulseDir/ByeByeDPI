// app/src/main/java/io/github/romanvht/byedpi/activities/SecretFeaturesActivity.kt

package be/activities

import android.os.Bundle
import io.github.romanvht.byedpi.fragments.SecretFeaturesFragment

class SecretFeaturesActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_secret_features)
        
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.container, SecretFeaturesFragment())
            .commit()
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "🔐 Секретные функции"
    }
}
