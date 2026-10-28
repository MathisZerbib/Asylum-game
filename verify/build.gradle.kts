plugins {
    kotlin("jvm") version "2.1.21"
    application
}

kotlin { jvmToolchain(21) }

sourceSets {
    main {
        kotlin {
            srcDir("../composeApp/src/commonMain/kotlin")
            include("com/lastasylum/game/engine/**", "com/lastasylum/game/render/**", "com/lastasylum/verify/**")
            srcDir("src/main/kotlin")
        }
    }
}

application { mainClass.set("com.lastasylum.verify.MainKt") }
