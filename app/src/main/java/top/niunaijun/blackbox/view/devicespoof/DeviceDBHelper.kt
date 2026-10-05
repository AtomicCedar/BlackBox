package top.niunaijun.blackbox.view.devicespoof

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable

/**
 * 机型库（assets/devices.db，SQLite 单表 models）：提供品牌列表、按品牌查型号、
 * 随机取一款机型。数据由 MobileModels（KHwang9883/MobileModels，CC BY-NC-SA 4.0）
 * 的 brands 目录下各品牌 markdown 转换生成（见 tools/gen_devices_db.py），仅保留
 * Android 手机品类（电视/平板/穿戴/Apple 已过滤）。
 *
 * 库在 assets 中不能直接打开，首次使用复制到数据库目录；以文件大小比对判断
 * 是否需要重新复制（应用升级换库时自动覆盖）。
 */
class DeviceDBHelper(context: Context) : Closeable {

    private val deviceDBFileName = "devices.db"
    private val deviceDBFile = context.getDatabasePath(deviceDBFileName)

    init {
        if (!deviceDBFile.exists() || deviceDBFile.length() != assetsSize(context)) {
            context.assets.open(deviceDBFileName).use { input ->
                deviceDBFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private val db: SQLiteDatabase by lazy {
        SQLiteDatabase.openDatabase(deviceDBFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    }

    private fun assetsSize(context: Context): Long {
        return try {
            context.assets.openFd(deviceDBFileName).length
        } catch (e: Throwable) {
            -1L
        }
    }

    /** brand -> 品牌中文名 */
    fun getAllBrand(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        db.rawQuery("select brand, brand_title from (select * from models group by brand)", null)
                .use { cursor ->
                    while (cursor.moveToNext()) {
                        map[cursor.getString(0)] = cursor.getString(1)
                    }
                }
        return map
    }

    fun getDevicesByBrand(brand: String): List<Device> {
        val list = mutableListOf<Device>()
        db.rawQuery(
                "select * from (select * from models group by model) where brand = ?",
                arrayOf(brand)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(Device(
                        brand = cursor.getString(cursor.getColumnIndexOrThrow("brand")),
                        brandTitle = cursor.getString(cursor.getColumnIndexOrThrow("brand_title")),
                        code = cursor.getString(cursor.getColumnIndexOrThrow("code")),
                        codeAlias = cursor.getString(cursor.getColumnIndexOrThrow("code_alias")),
                        dtype = cursor.getString(cursor.getColumnIndexOrThrow("dtype")),
                        model = cursor.getString(cursor.getColumnIndexOrThrow("model")),
                        modelName = cursor.getString(cursor.getColumnIndexOrThrow("model_name")),
                        verName = cursor.getString(cursor.getColumnIndexOrThrow("ver_name"))
                ))
            }
        }
        return list
    }

    /** 随机取一款机型（一键随机整机用） */
    fun randomDevice(): Device? {
        db.rawQuery("select * from models order by random() limit 1", null).use { cursor ->
            if (cursor.moveToFirst()) {
                return Device(
                        brand = cursor.getString(cursor.getColumnIndexOrThrow("brand")),
                        brandTitle = cursor.getString(cursor.getColumnIndexOrThrow("brand_title")),
                        code = cursor.getString(cursor.getColumnIndexOrThrow("code")),
                        codeAlias = cursor.getString(cursor.getColumnIndexOrThrow("code_alias")),
                        dtype = cursor.getString(cursor.getColumnIndexOrThrow("dtype")),
                        model = cursor.getString(cursor.getColumnIndexOrThrow("model")),
                        modelName = cursor.getString(cursor.getColumnIndexOrThrow("model_name")),
                        verName = cursor.getString(cursor.getColumnIndexOrThrow("ver_name"))
                )
            }
        }
        return null
    }

    override fun close() {
        db.close()
    }
}

/**
 * 机型库一行。映射到设备字段：brand -> 厂商、model -> 型号、code_alias -> Device、
 * model_name -> 市场名称（传播名）、ver_name -> 变体备注。
 */
data class Device(
        val brand: String?,
        val brandTitle: String?,
        val code: String?,
        val codeAlias: String?,
        val dtype: String?,
        val model: String?,
        val modelName: String?,
        val verName: String?
)
