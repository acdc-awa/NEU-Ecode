package com.neboer.ecode

/** 余额查询结果:区分网络不可达/超时与其他失败(会话失效、接口报错等) */
sealed class BalanceResult {
    data class Success(val value: String) : BalanceResult()

    /** 网络不可达/超时(如断网) */
    object NetworkUnreachable : BalanceResult()

    /** 会话失效、接口报错、页面结构变化等其他失败 */
    object Failed : BalanceResult()
}

/**
 * 余额数据源抽象。当前唯一实现:
 * - [PortalClient] 门户"个人数据"卡片JSON接口(校内外公网均可达)
 * 一卡通自助查询(ecard)解析已废弃移除:其数值与门户不同步的问题已由校方修复,
 * 统一只走门户接口。
 */
interface BalanceSource {
    /** 返回余额数值(如 "10.89");不抛异常。 */
    fun fetchBalance(): BalanceResult
}
