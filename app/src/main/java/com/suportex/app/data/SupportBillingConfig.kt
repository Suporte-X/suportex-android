package com.suportex.app.data

import com.suportex.app.data.model.CreditPackageRecord

object SupportBillingConfig {
    const val OFFICIAL_WHATSAPP_NUMBER = "5565996497550"

    val defaultCreditPackages: List<CreditPackageRecord> = listOf(
        CreditPackageRecord(
            id = "pkg-1",
            name = "1 atendimento",
            supportCount = 1,
            priceCents = 2000,
            active = true,
            displayOrder = 1
        ),
        CreditPackageRecord(
            id = "pkg-3",
            name = "3 atendimentos",
            supportCount = 3,
            priceCents = 5000,
            active = true,
            displayOrder = 2
        ),
        CreditPackageRecord(
            id = "pkg-7",
            name = "7 atendimentos",
            supportCount = 7,
            priceCents = 10000,
            active = true,
            displayOrder = 3
        )
    )
}
