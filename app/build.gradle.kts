plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.pstranslate"
    compileSdk = 34
    
    defaultConfig {
        applicationId = "com.example.pstranslate"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions { 
        jvmTarget = "17" 
    }
    
    // ✅ تفعيل ViewBinding (اختياري ولكن يسهل ربط العناصر لاحقاً)
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // مكتباتك الأصلية
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")

    // ✅ المكتبات الجديدة للتصميم العصري
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
