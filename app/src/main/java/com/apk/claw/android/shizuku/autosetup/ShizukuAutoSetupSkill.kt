package com.apk.claw.android.shizuku.autosetup

import com.apk.claw.android.octopus_mobile.skill.PromptSkillStore

/**
 * 内置技能「自动配置 Shizuku」的种子。
 *
 * 思路(用户点子):不写死各家 ROM 的无障碍节点,而是把「怎么开 Shizuku」写成一段**给视觉 Agent 的剧本**,
 * Agent 用自己的 look_at_screen(VLM 看屏)+ 点击工具自适应任何 ROM 的设置界面,读出配对码后交给
 * [ShizukuAutoSetupTool] 完成握手。关键词命中(shizuku/无线调试/配对/高级权限)时由 PromptSkillStore
 * 注入 System Prompt。
 *
 * seedIfAbsent 只在**缺失**时写入,尊重用户在技能页的开关/编辑。
 */
object ShizukuAutoSetupSkill {

    private const val NAME = "自动配置 Shizuku"
    private const val ID = "builtin-shizuku-autosetup"

    private val BODY = """
        当用户想开启 / 配置 Shizuku(或提到无线调试、adb 配对、高级权限、开小窗/截屏增强)时,按下面步骤用你**自己的**工具做,不要让用户手动折腾:

        前置判断:
        - 仅 Android 11+ 能单机无线配对;更低版本告诉用户需插一次电脑,停止。
        - 先 take_screenshot / get_installed_apps 确认已装 Shizuku(包名 moe.shizuku.privileged.api);没装先引导安装再继续。

        步骤:
        1. 打开系统设置 →「开发者选项」(没开就引导用户在「关于手机」连点版本号 7 次)→ 找到「无线调试」并**打开**;弹「允许无线调试?」点允许。用 look_at_screen 确认已开。
        2. 点进「无线调试」→「使用配对码配对设备」。用 look_at_screen **看清**弹窗里的三样:IP、端口、6 位配对码(注意端口是这个**配对子弹窗**的,不是主页那个连接端口)。
        3. 调 shizuku_auto_setup(action="pair", host=<IP>, port=<配对端口>, code=<6位码)。失败就重开子弹窗取**新配对码**再试(码会过期)。
        4. 配对成功后调 shizuku_auto_setup(action="start")自动发现并拉起 Shizuku;若返回说自动发现失败,回无线调试**主页**用 look_at_screen 读「IP 地址和端口」,带 host/port 再调一次 start。
        5. 调 shizuku_auto_setup(action="status")确认;若 Shizuku 弹出授权框,点「允许」把权限给 octopus。就绪后告诉用户完成。

        提醒用户:手机重启后无线调试与 Shizuku 都会停,需要再跑一次(配对通常还记得,多数只要重新 start)。root 手机可在 Shizuku 里「以 Root 启动」一劳永逸。
    """.trimIndent()

    /** 首次缺失时种入;已存在(含被用户关掉/改过)则不动。 */
    fun seedIfAbsent() {
        runCatching {
            if (PromptSkillStore.all().any { it.name == NAME }) return
            PromptSkillStore.add(
                PromptSkillStore.PromptSkill(
                    id = ID,
                    name = NAME,
                    description = "用户想开启/配置 Shizuku、无线调试、adb 配对或高级权限时,自动看屏配对并拉起 Shizuku",
                    body = BODY,
                    enabled = true,
                    source = "builtin",
                ),
            )
        }
    }
}
