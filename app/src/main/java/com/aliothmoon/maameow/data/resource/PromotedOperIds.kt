package com.aliothmoon.maameow.data.resource

/**
 * 升变形态干员 ID，不参与花名册，[ResourceDataManager] 的虚拟干员集合从这里取
 *
 * 干员识别结果保留原形态 ID 不归一：core 编队预检按形态 ID 对位
 */
object PromotedOperIds {

    val ids: Set<String> = setOf(
        "char_1001_amiya2", // 阿米娅-WARRIOR
        "char_1037_amiya3", // 阿米娅-MEDIC
    )
}
