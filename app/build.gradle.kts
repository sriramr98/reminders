plugins { id("com.android.application") }
android {
    namespace = "com.sriram.callreminder"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sriram.callreminder"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("junit:junit:4.13.2")
}
