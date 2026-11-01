package com.xjw.qalbum.ui.onboarding

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xjw.qalbum.AppInfo
import com.xjw.qalbum.ui.common.openUrl
import com.xjw.qalbum.ui.components.BackButton
import com.xjw.qalbum.ui.components.MarkdownText

/**
 * 用户协议的版本：改了协议内容就加一，老用户下次打开要重新同意（见 needsAgreement），同意之前不收集任何统计。
 * 2：加入匿名统计与崩溃日志（Firebase）。
 */
const val AGREEMENT_VERSION = 2

/** 用户协议与免责声明（含隐私说明），Markdown。首次引导的第二页要看满 10 秒、拉到底才能同意，「设置 → 关于」里可以再看 */
internal val USER_AGREEMENT = """
    更新日期：2026 年 11 月 1 日

    欢迎使用 QAlbum（以下简称“本应用”）。在使用本应用前，请您务必仔细阅读并充分理解本协议的全部内容，特别是以**加粗**形式提示的条款。您点击“同意并继续”或以其他方式使用本应用，即视为您已阅读、理解并同意受本协议约束。如您不同意本协议的任何内容，请立即停止使用并卸载本应用。

    ## 一、数据安全与风险提示

    1. **数据无价，请谨慎操作。**本应用提供的删除、压缩、清理等功能会直接修改或删除您设备上的文件。永久删除、清空回收站、压缩替换原图、清理 QQ 缓存目录、删除视频等操作一经执行即不可撤销，相关数据可能无法恢复。
    2. 在执行任何清理操作前，请您确认所选内容，并**自行备份重要的图片、视频及聊天数据**。
    3. 本应用的回收站、原图备份等功能仅作为辅助手段，不能替代完整的数据备份，也不保证在任何情况下均能恢复数据。
    4. **因使用本应用（包括但不限于误操作、设备或系统异常、第三方应用变更等原因）导致的任何数据丢失、损坏或其他损失，均由您自行承担。**

    ## 二、高级功能特别说明

    1. 本应用的部分高级功能（如“读取聊天来源”）需要在 Root 权限下复制、读取并解密腾讯 QQ 的本地数据库，以便将缓存图片与会话、发送人相关联。高级功能默认关闭，仅可在“设置 → 文件访问”中选择 Root 方式后手动开启，首次开启时将再次提示相关风险。
    2. 上述数据库的读取与解密方法来源于互联网公开资料，并非腾讯公司提供或授权的接口。本应用与腾讯公司不存在任何关联，未获得其认可、授权或支持。
    3. **使用上述功能可能违反《腾讯 QQ 软件许可及服务协议》等相关规定，并可能导致账号被限制或封禁、数据异常等风险。是否使用由您自行判断，由此产生的一切后果由您自行承担。**
    4. 腾讯 QQ 的版本更新可能导致相关功能失效或结果不准确，本应用不对其可用性与准确性作任何保证。

    ## 三、使用限制

    1. 本应用仅供个人学习与研究使用，不得用于任何商业用途或违法违规用途。
    2. 您应当清楚了解每项操作的作用与后果，并对自己的操作行为负责。
    3. 您应当遵守所在国家或地区的法律法规，以及相关应用（包括但不限于腾讯 QQ）的用户协议与服务条款，确保您的使用方式符合其规定。**因违反上述规定产生的任何纠纷、损失或法律责任，均由您自行承担。**

    ## 四、隐私说明

    1. 本应用对图片、视频、聊天记录等数据的处理均在您的设备本地完成，**不会收集或上传您的图片、视频、文件名及路径、聊天内容、QQ 号码、群聊与联系人信息等个人数据。**
    2. 为改进功能与修复问题，本应用在您同意本协议后启用 Google 提供的 Firebase Analytics（统计分析）与 Firebase Crashlytics（崩溃报告）服务：“匿名统计”记录各功能的使用次数、耗时与结果、处理的图片数量与大小以及修改过的设置项；“崩溃日志”在应用崩溃或出错时上传错误信息，其中的文件路径、长数字等内容在上传前移除。两者均附带设备型号、系统版本、应用版本、国家或地区等基本信息。
    3. 上述数据仅通过随机生成的标识符关联，不包含您的身份信息，不用于广告，也不会出售或提供给其他第三方。本应用不包含广告。
    4. 您可以随时在“设置 → 关于”中随时关闭“匿名统计”与“自动上传崩溃日志”。**全部关闭后，本应用仅记录统计活跃用户所必需的打开次数与使用时长。**
    5. 检查更新时，本应用仅读取 GitHub 上公开的版本信息，请求中不包含任何设备信息或个人信息。
    6. 上述网络服务分别由 Google 与 GitHub 提供，其对网络请求信息（如 IP 地址）的处理适用各自的隐私政策（Google 隐私政策：https://policies.google.com/privacy）。

    ## 五、开源与发布渠道

    1. 本应用为开源软件，永久免费，不含任何付费内容。任何以本应用名义收取费用的行为均与开发者无关。
    2. 本应用仅在 GitHub 项目主页（${AppInfo.PROJECT_URL}）发布，开发者未通过其他任何渠道发布或授权发布本应用。
    3. **请您注意核对应用来源。通过其他渠道获得的安装包均未经授权，可能已被篡改或植入恶意代码，请勿安装使用；由此造成的任何损失由您自行承担。**

    ## 六、免责声明

    1. 本应用按“现状”提供，开发者不对其适用性、完整性、可靠性及无错误性作任何明示或默示的保证。
    2. 在法律允许的最大范围内，开发者不对因使用或无法使用本应用而产生的任何直接、间接、附带或衍生的损失承担责任。
    3. 开发者有权根据需要更新本协议，更新后的协议将随新版本发布。您继续使用本应用，即视为接受更新后的协议。
""".trimIndent()

@Composable
internal fun AgreementText(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    MarkdownText(USER_AGREEMENT, onOpenUrl = { openUrl(context, it) }, modifier = modifier)
}

/** 「设置 → 关于」里查看用户协议与免责声明 */
@Composable
fun AgreementScreen(onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("用户协议与免责声明") },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        AgreementText(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 24.dp)
                .navigationBarsPadding(),
        )
    }
}
