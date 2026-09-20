/*
 * Copyright (C) 2025 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-3.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package net.ankio.auto.xposed.hooks.qianji.debt/*
 * Copyright (C) 2024 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-3.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *   limitations under the License.
 */


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.ankio.auto.xposed.core.logger.XposedLogger
import net.ankio.auto.xposed.hooks.qianji.impl.AssetPreviewPresenterImpl
import net.ankio.auto.xposed.hooks.qianji.impl.BookManagerImpl
import net.ankio.auto.xposed.hooks.qianji.models.QjAssetAccountModel
import net.ankio.auto.xposed.hooks.qianji.models.QjBillModel
import net.ankio.auto.xposed.hooks.qianji.models.QjBookModel
import org.ezbook.server.db.model.BillInfoModel


/**
 * 还款
 */

class ExpendRepaymentUtils :
    BaseDebt() {
    /**
     * 同步还款到钱迹：扣款账户 -> 债主。
     */
    override suspend fun sync(billModel: BillInfoModel) = withContext(Dispatchers.IO) {
        // 扣款账户（如微信）
        val accountFrom = getAccountFrom(billModel)
        // 债主（借入债务账户）
        val accountTo = getAccountTo(billModel)

        val book = BookManagerImpl.getBookByName(billModel.bookName)

        XposedLogger.d("repayment ${billModel.money} ${billModel.accountNameFrom} -> ${billModel.accountNameTo}")

        // 本金不足时拆出利息账单
        val (bill1, bill2) = splitBill(billModel, accountFrom)

        if (bill2 != null) {
            val bill = updateBill(bill2, 10, book, accountFrom, accountTo)
            saveBill(bill)
        }

        // 更新债主还款进度
        updateLoan(bill1!!, accountTo)
        // 从扣款账户扣钱并持久化
        updateAsset(accountFrom, accountTo, bill1)

        if (bill1.money > 0) {
            val bill = updateBill(bill1, 9, book, accountFrom, accountTo)
            saveBill(bill)
        }

        pushBill()
    }

    private suspend fun splitBill(
        billModel: BillInfoModel,
        accountFrom: QjAssetAccountModel
    ): List<BillInfoModel?> = withContext(Dispatchers.IO) {
        val assetMoney = accountFrom.getMoney()
        if (assetMoney < billModel.money) {
            val interest = billModel.money - assetMoney
            val bill1 = billModel.copy().apply {
                money = assetMoney
            }
            val bill2 = billModel.copy().apply {
                money = interest
                remark = "债务利息"
            }
            return@withContext listOf(bill1, bill2)
        }
        return@withContext listOf(billModel, null)
    }


    /**
     * 获取债主账户（借入债务）。
     */
    private suspend fun getAccountTo(billModel: BillInfoModel): QjAssetAccountModel =
        withContext(Dispatchers.IO) {
            return@withContext AssetPreviewPresenterImpl.getAssetByName(billModel.accountNameTo)
                ?: throw RuntimeException("债主不存在 key=accountname;value=${billModel.accountNameTo}")
        }


    /**
     * 获取扣款账户（如微信）。
     */
    private suspend fun getAccountFrom(billModel: BillInfoModel): QjAssetAccountModel =
        withContext(Dispatchers.IO) {
            return@withContext AssetPreviewPresenterImpl.getAssetByName(billModel.accountNameFrom)
                ?: throw RuntimeException("扣款账户不存在 key=accountname;value=${billModel.accountNameFrom}")
        }


    /**
     * 更新债务
     */

    private suspend fun updateLoan(billModel: BillInfoModel, accountTo: QjAssetAccountModel) =
        withContext(Dispatchers.IO) {
            // 债务
            val loan = accountTo.getLoanInfo()

            // {"a":0,"b":"2024-07-17","c":"","e":-12.0,"f":0.0}
            // f=TotalPay 已还金额
            // e=money 待还金额
            //
            loan.setTotalpay(-billModel.money)

            accountTo.setLoanInfo(loan)
            accountTo.addMoney(-billModel.money)
        }

    /**
     * 更新资产余额：从扣款账户扣钱，并持久化债主与扣款账户。
     * 债主余额已在 [updateLoan] 中按借入债务符号更新，此处不得再加回。
     */
    private suspend fun updateAsset(
        accountFrom: QjAssetAccountModel,
        accountTo: QjAssetAccountModel,
        billModel: BillInfoModel,
    ) = withContext(Dispatchers.IO) {
        // 还款：钱从扣款账户流出
        accountFrom.addMoney(-billModel.money)
        updateAssets(accountTo)
        updateAssets(accountFrom)
    }


    /**
     * 构建钱迹债务还款账单。
     *
     * 钱迹抓包约定：
     * - type=9 本金：assetId=债主，fromId=扣款账户
     * - type=10 利息：assetId=扣款账户，fromId=债主
     * descinfo 固定为「扣款账户->债主」，与模块展示方向一致。
     */
    private suspend fun updateBill(
        billModel: BillInfoModel,
        type: Int,
        book: QjBookModel,
        accountFrom: QjAssetAccountModel,
        accountTo: QjAssetAccountModel
    ): QjBillModel = withContext(Dispatchers.IO) {
        val money = billModel.money
        val remark = billModel.remark
        val time = billModel.time / 1000
        val imageList = ArrayList<String>()

        val bill = QjBillModel.newInstance(
            type,
            remark,
            money,
            time,
            imageList
        )

        // type=10 利息：assetId=扣款账户，fromId=债主
        // type=9 本金：assetId=债主，fromId=扣款账户
        if (billModel.remark.contains("利息")) {
            QjBillModel.setZhaiwuCurrentAsset(bill, accountFrom)
            QjBillModel.setZhaiwuAboutAsset(bill, accountTo)
        } else {
            QjBillModel.setZhaiwuCurrentAsset(bill, accountTo)
            QjBillModel.setZhaiwuAboutAsset(bill, accountFrom)
        }

        bill.setBook(book)
        // 还款方向：扣款账户 -> 债主（与 ExpendLending / 模块 UI 一致）
        bill.setDescinfo("${accountFrom.getName()}->${accountTo.getName()}")

        bill
    }
}
