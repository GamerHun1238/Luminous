package me.liuli.luminous.utils.jvm

import me.liuli.luminous.Luminous
import me.liuli.luminous.agent.Agent
import me.liuli.luminous.utils.extension.getMethodByName
import me.liuli.luminous.utils.extension.getMethodsByName
import me.liuli.luminous.utils.extension.signature
import me.liuli.luminous.utils.misc.HttpUtils
import me.liuli.luminous.utils.misc.logError
import me.liuli.luminous.utils.misc.logInfo
import me.liuli.luminous.utils.misc.logWarn
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import javax.swing.*

object AccessUtils {
    val SRG_URL = "${Luminous.RESOURCE}/srg"
    val SRG2MCP_VERSION = "stable_22"
    val SUPPORT_VERSION_LIST =
        arrayOf("1.7.10",
            "1.8", "1.8.8", "1.8.9",
            "1.9", "1.9.2", "1.9.4",
            "1.10", "1.10.2",
            "1.11", "1.11.1", "1.11.2",
            "1.12", "1.12.1", "1.12.2")

    val srgCacheDir = File(Luminous.dataDir, "srg")

    val currentEnv = try {
        val clazz = getClassByName("net.minecraft.client.Minecraft")
        if(clazz.declaredFields.any { it.name == "thePlayer" }) {
            MinecraftEnv.MCP
        } else {
            MinecraftEnv.SEARGE
        }
    } catch (e: ClassNotFoundException) {
        MinecraftEnv.NOTCH
    }

    val classOverrideMap = mutableMapOf<String, String>() // example: net.minecraft.client.Minecraft -> ave
    val fieldOverrideMap = mutableMapOf<String, String>() // example: net/minecraft/client/Minecraft/thePlayer -> (net/minecraft/client/Minecraft/field_71439_g ->) ave/h
    val methodOverrideMap = mutableMapOf<String, String>() // like fieldOverrideMap, but for methods

    init {
        if(!srgCacheDir.exists())
            srgCacheDir.mkdirs()
    }

    fun initByTitle() {
        val title = getClassByName("org.lwjgl.opengl.Display")
            .getDeclaredMethod("getTitle")
            .invoke(null) as String

        if(title.matches(Regex("Minecraft [0-9.]{3,6}"))) {
            init(title.substring("Minecraft ".length))
        } else {
            logError("Failed to detect current minecraft version")

            val panel = JPanel()
            panel.add(JLabel("Select your minecraft version:"))
            val model = DefaultComboBoxModel<String>()
            SUPPORT_VERSION_LIST.forEach { model.addElement(it) }
            val comboBox = JComboBox(model)
            panel.add(comboBox)

            JOptionPane.showConfirmDialog(null, panel, Luminous.TITLE, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE)

            init(comboBox.selectedItem.toString())
        }
    }

    fun init(version: String) {
        logInfo("Loading srg map for Minecraft $version with $currentEnv env")

        // download notch-srg map if in notch env
        if(currentEnv == MinecraftEnv.NOTCH) {
            val srgMapFile = File(srgCacheDir, "$version.srg")
            HttpUtils.downloadIfNotExists("$SRG_URL/$version.srg", File(srgCacheDir, "$version.srg"))
            phaseSrgMap(srgMapFile)
        }

        // phase srg-mcp map after notch-srg map
        if(currentEnv != MinecraftEnv.MCP) {
            val mcpMapFile = File(srgCacheDir, "$SRG2MCP_VERSION.srg")
            HttpUtils.downloadIfNotExists("$SRG_URL/$SRG2MCP_VERSION.srg", mcpMapFile)
            phaseSrgMap(mcpMapFile)
        }

        flattenSrgCache()

        logInfo("${classOverrideMap.size} Classes ${methodOverrideMap.size} Methods ${fieldOverrideMap.size} Fields was loaded!")
    }

    private fun flattenSrgCache() {
        val cache = mutableMapOf<String, String>()

        fieldOverrideMap.forEach {  (patched, original) ->
            val arr = original.split("!")
            val arr1 = patched.split("!")
            cache[arr[0] + arr1.subList(1, arr1.size).joinToString("!", "!")] = original
        }

        fieldOverrideMap.clear()
        cache.forEach(fieldOverrideMap::put)
        cache.clear()

        methodOverrideMap.forEach {  (patched, original) ->
            val arr = original.split("!")
            val arr1 = patched.split("!")
            cache[arr[0] + arr1.subList(1, arr1.size).joinToString("!", "!")] = original
        }

        methodOverrideMap.clear()
        cache.forEach(methodOverrideMap::put)
    }

    private fun phaseSrgMap(srgFile: File) {
        srgFile.readLines(StandardCharsets.UTF_8).forEach {
            val args = it.split(" ")

            when {
                it.startsWith("CL:") -> {
                    val original = args[1].replace("/" , ".")
                    val patched = args[2].replace("/" , ".")

                    if (classOverrideMap.containsKey(original)) {
                        classOverrideMap[patched] = classOverrideMap[original]!!
                        classOverrideMap.remove(original)
                    } else {
                        classOverrideMap[patched] = original
                    }
                }

                it.startsWith("FD:") -> {
                    val original = args[1].substring(0, args[1].lastIndexOf('/')).replace('/', '.') + "!" + args[1].substring(args[1].lastIndexOf('/') + 1)
                    val patched = args[2].substring(0, args[2].lastIndexOf('/')).replace('/', '.') + "!" + args[2].substring(args[2].lastIndexOf('/') + 1)

                    if (fieldOverrideMap.containsKey(original)) {
                        fieldOverrideMap[patched] = fieldOverrideMap[original]!!
                        fieldOverrideMap.remove(original)
                    } else {
                        fieldOverrideMap[patched] = original
                    }
                }

                it.startsWith("MD:") -> {
                    val original = args[1].substring(0, args[1].lastIndexOf('/')).replace('/', '.') + "!" + args[1].substring(args[1].lastIndexOf('/') + 1) + "!" + args[2]
                    val patched = args[3].substring(0, args[3].lastIndexOf('/')).replace('/', '.') + "!" + args[3].substring(args[3].lastIndexOf('/') + 1) + "!" + args[4]

                    if (methodOverrideMap.containsKey(original)) {
                        methodOverrideMap[patched] = methodOverrideMap[original]!!
                        methodOverrideMap.remove(original)
                    } else {
                        methodOverrideMap[patched] = original
                    }
                }
            }
        }
    }

    /**
     * get class by name from instrumentation classloader
     * this able to find wrapped classes
     */
    fun getClassByName(name: String)
            = Agent.instrumentation.allLoadedClasses.find { it.name == name } ?: throw ClassNotFoundException(name)

    fun getObfClassByName(name: String)
            = getClassByName(classOverrideMap[name] ?: name)

    fun getObfFieldByName(clazz: Class<*>, name: String)
            = clazz.getDeclaredField(fieldOverrideMap[clazz.name + "!" + name]?.split("!")?.get(1) ?: name)

    fun getObfMethodByName(clazz: Class<*>, name: String, signature: String): Method {
        val method = methodOverrideMap[clazz.name + "!" + name + "!" + signature]
            ?: return (clazz.getMethodsByName(name).find { it.signature == signature } ?: throw NoSuchMethodException(name))

        val args = method.split("!")
        return clazz.getMethodsByName(args[1]).let {
            if (it.size>1) {
                it.find { method1 -> method1.signature == args[2].replace(".", "/") }
            } else {
                it.firstOrNull()
            }
        } ?: throw NoSuchMethodException(name)
    }

    enum class MinecraftEnv {
        NOTCH,
        SEARGE,
        MCP
    }
}