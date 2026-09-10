package com.nothing.camera2magic.utils

import android.content.Context
import android.hardware.camera2.CameraManager

/**
 * 宿主侧相机清点：**纯诊断展示**（主页只显示一个数字），不参与任何 Hook 门控或媒体选择。
 *
 * 只数 `getCameraIdList()` 的 id 个数，不做任何分类或推断，理由有两条：
 * 1. 枚举本身不要求 CAMERA 权限，但一部分特征键是受限的（各机型的受限集合由 HAL 声明，
 *    运行时只能靠 `CameraCharacteristics.getKeysNeedingPermission()` 判定）——分类所需的
 *    数据在宿主侧本来就取不全；
 * 2. id 数不等于物理镜头数（多颗镜头会被聚合成同一个逻辑相机 id，各家暴露方式还不统一），
 *    所以「几颗镜头」「这个 id 里藏着谁」这类数字在这里给不出可靠答案，不如不给。
 *
 * 焦距级别的镜头识别必须留在目标进程里做（见 Camera2Hooker 的 `lens:` 日志：那边的查询
 * 发生在目标应用进程里，用的是它自己的身份，而相机类应用必然持有 CAMERA）。
 */
object CameraInventory {

    /** 系统暴露给普通应用的相机 id 数量；枚举失败（设备策略禁用、无相机服务等）返回 null。 */
    fun count(context: Context): Int? = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return@runCatching null
        manager.cameraIdList.size
    }.getOrNull()
}
