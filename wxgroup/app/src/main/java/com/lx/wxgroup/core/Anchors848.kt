package com.lx.wxgroup.core

/**
 * 8.0.48（GP 版，versionCode 2588）实测锚点。
 *
 * 这些值全部来自实机 logcat 的运行时 dump，不是猜的。
 * 微信每个版本重排混淆，换版本必须重新跑一遍 UiProbe 定位，
 * 然后在这里新增一组常量，用 VersionGate.cacheKey（versionName_versionCode）区分。
 *
 * 定位方式（可复现）：
 *   1. hook androidx.fragment.app.Fragment.performResume/onHiddenChanged → 打印 Fragment 类名
 *   2. 从 Fragment 的 View 树里找 WxRecyclerView → getAdapter() → 打印 adapter 类名
 *   3. adapter 的 List 字段里第一项的 contact 字段 → 打印它的类名和全部 String 字段
 */
object Anchors848 {

    const val VERSION_NAME = "8.0.48"
    const val VERSION_CODE = 2588L

    /** 通讯录页 Fragment（切到「通讯录」Tab 时可见） */
    const val CONTACT_FRAGMENT = "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment"

    /** 通讯录列表的 RecyclerView 类型（微信自定义） */
    const val CONTACT_LIST_VIEW = "com.tencent.mm.view.recyclerview.WxRecyclerView"

    /** 通讯录 Adapter */
    const val CONTACT_ADAPTER = "ju2.s0"

    /** Adapter 里存展示条目的字段名，元素是「条目」而非 Contact 本身 */
    const val ADAPTER_DATA_FIELD = "data"

    /** 条目对象里持有 Contact 的字段名 */
    const val ITEM_CONTACT_FIELD = "d"

    /** 微信联系人实体（等价于 com.tencent.mm.storage.Contact） */
    const val CONTACT_CLASS = "com.tencent.mm.storage.h4"

    // ---- Contact 的关键字段（字段名未混淆，很走运） ----
    const val FIELD_USERNAME = "field_username"     // wxid，主键
    const val FIELD_NICKNAME = "field_nickname"     // 昵称
    const val FIELD_REMARK = "field_conRemark"      // 备注
    const val FIELD_ALIAS = "field_alias"           // 微信号
    const val FIELD_LABEL_IDS = "field_contactLabelIds" // 官方标签 id（不要写，只可读）

    /** 稳定 getter：方法名未混淆，可作为兜底取 wxid 的手段 */
    const val METHOD_PRIMARY_KEY = "getPrimaryKeyValue"

    /** 数据表：rcontact（说明这套结构是官方联系人表） */
    const val TABLE_NAME = "rcontact"
}
