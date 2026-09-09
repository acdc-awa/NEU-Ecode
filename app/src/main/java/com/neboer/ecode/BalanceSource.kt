package com.neboer.ecode

/** 余额查询结果:区分网络不可达(校外访问仅内网一卡通的典型表现)与其他失败 */
sealed class BalanceResult {
    data class Success(val value: String) : BalanceResult()

    /** 网络不可达/超时(如校外访问一卡通、断网) */
    object NetworkUnreachable : BalanceResult()

    /** 会话失效、接口报错、页面结构变化等其他失败 */
    object Failed : BalanceResult()
}

/**
 * 余额数据源抽象。现有两个实现:
 * - [EcardClient] 一卡通自助查询(权威数值,仅校园网可达)
 * - [PortalClient] 门户"个人数据"卡片JSON接口(备用,当前与ecard数值不同步,公网可达)
 * 切换主界面数据源时,把 MainActivity 中实际调用的实现换掉即可。
 */
interface BalanceSource {
    /** 返回余额数值(如 "10.89");不抛异常。 */
    fun fetchBalance(): BalanceResult
}
