import com.android.build.api.dsl.ApplicationDefaultConfig
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.extra

var ExtensionAware.baseVersionCode: Int
    get() = extra.get("baseVersionCode") as Int
    set(value) = extra.set("baseVersionCode", value)

@Suppress("UnusedReceiverParameter")
fun Project.configureAndroid(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        compileSdk = AndroidConfig.COMPILE_SDK

        defaultConfig {
            if (this is ApplicationDefaultConfig) {
                targetSdk = AndroidConfig.TARGET_SDK
            }
            minSdk = AndroidConfig.MIN_SDK
        }

        buildFeatures {
            shaders = false
        }
    }
}