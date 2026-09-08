package com.neboer.ecode

/**
 * 余额数据源抽象。现有两个实现:
 * - [EcardClient] 一卡通自助查询(权威数值,主界面当前在用)
 * - [PortalClient] 门户"个人数据"卡片JSON接口(备用,当前与ecard数值不同步)
 * 切换主界面数据源时,把 MainActivity 中实际调用的实现换掉即可。
 */
interface BalanceSource {
    /** 返回余额数值(如 "10.89"),失败返回 null。不抛异常。 */
    fun fetchBalance(): String?
}
