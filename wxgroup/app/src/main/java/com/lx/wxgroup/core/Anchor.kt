package com.lx.wxgroup.core

/**
 * 逆向锚点清单。
 *
 * 每个枚举项对应宿主里一个「随版本改名、但行为不变」的位置。
 * M1 阶段用 Frida 粗定位 -> 提炼稳定特征 -> 写成 DexKit matcher -> 结果按锚点缓存。
 * 千万不要把找到的类名硬编码到业务代码里。
 */
enum class Anchor(
    val key: String,
    val usage: String,
) {
    CONTACT_FRAGMENT(
        "contact_fragment",
        "通讯录页 Fragment/Activity，用来判断当前界面和承载注入容器"
    ),

    CONTACT_ADAPTER(
        "contact_adapter",
        "联系人列表 RecyclerView.Adapter，数据源入口"
    ),

    CONTACT_USERNAME_GETTER(
        "contact_username",
        "取 wxid 的方法，返回值 String，结果形如 wxid_xxx / gh_xxx"
    ),

    CONTEXT_MENU_CREATE(
        "context_menu",
        "长按/上下文菜单创建方法，注入「加入分组」"
    ),

    CONTACT_TAB_CONTAINER(
        "contact_tab_container",
        "通讯录顶部容器 View，用于挂「我的分组」入口"
    ),

    LIST_REFRESH(
        "list_refresh",
        "重建/刷新列表的方法，切换分组后触发重绘"
    ),

    LABEL_ENTRY(
        "label_entry",
        "官方「标签」入口 UI，作为插入自定义分组入口的锚点；字符串需中英兜底"
    ),
    ;

    companion object {
        fun fromKey(key: String): Anchor? = entries.firstOrNull { it.key == key }
    }
}
