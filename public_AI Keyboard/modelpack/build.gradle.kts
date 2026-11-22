import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.asset.pack)
}

assetPack {
    packName.set("modelpack")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

val modelFile = rootProject.layout.projectDirectory.file(
    "models/gemma-3-1b-it.Q4_K_M.gguf"
)
val stageBundledModel by tasks.registering(Copy::class) {
    dependsOn("verifyBundledModel")
    from(modelFile)
    into(layout.projectDirectory.dir("src\\main\\assets\\models"))
}

val verifyBundledModel by tasks.registering {
    inputs.file(modelFile).withPropertyName("modelFile")
    doLast {
        val source = inputs.files.singleFile
        if (!source.isFile || source.length() == 0L) {
            throw GradleException("Required model is missing or empty: $source")
        }
    }
}

tasks.configureEach {
    if (name.contains("AssetPack", ignoreCase = true)) {
        dependsOn(stageBundledModel)
    }
}
