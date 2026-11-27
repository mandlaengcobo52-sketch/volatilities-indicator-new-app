
                val headerView = text(
                    "${displayIndexName(symbol)} · RUNNING POSITIONS   ＋",
                    14f, indexNameColor(symbol), true
                )
                c.addView(headerView)
                c.addView(space(8))

                // Compact summary window: always visible when the card is collapsed.
                val summaryWindow = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = rounded(
                        if (darkInterface) Color.rgb(8, 28, 32) else Color.rgb(240, 247, 250),
                        indexNameColor(symbol), 10
                    )
                }
                c.addView(summaryWindow)

                // Full trade details: hidden until the card is expanded.
                val detailsContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                }
                c.addView(detailsContainer)

                headerView.setOnClickListener {
                    val expanded = detailsContainer.visibility == View.VISIBLE
                    detailsContainer.visibility = if (expanded) View.GONE else View.VISIBLE
                    headerView.text =
                        "${displayIndexName(symbol)} · RUNNING POSITIONS   ${if (expanded) "＋" else "−"}"
                }

                indexPositions.forEachIndexed { index, p ->
                    if (index > 0) {
                        summaryWindow.addView(space(8))
                        summaryWindow.addView(divider())
                        summaryWindow.addView(space(8))
                        detailsContainer.addView(space(8))
                        detailsContainer.addView(divider())
                        detailsContainer.addView(space(8))
                    }

                    // COMPACT SUMMARY — remains visible while collapsed.
                    summaryWindow.addView(text(
                        "${p.role} · ${p.direction}",
                        12f, if (p.direction == "RISE") green else red, true
                    ))

                    if (p.status == "OPEN") {
                        val countdownView = text(
                            "REMAINING TIME: ${formatContractCountdown(p.expiryEpoch)}",
                            13f, blue, true
                        )
                        tradeCountdownViews[p.localId] = countdownView
                        summaryWindow.addView(countdownView)
                    }

                    val profitView = text(
                        "PROFIT / LOSS: ${formatMoney(p.profit)}",
                        14f, if (p.profit >= 0.0) green else red, true
                    )
                    tradeProfitViews[p.localId] = profitView
                    summaryWindow.addView(profitView)

                    val trailingView = text(
                        trailingStopDisplayText(p),
                        12f, if (p.trailingActivated) green else blue, true
                    )
                    tradeTrailingViews[p.localId] = trailingView
                    summaryWindow.addView(trailingView)

                    // EXPANDED DETAILS.
                    detailsContainer.addView(space(8))
                    detailsContainer.addView(text("TRADE LIFECYCLE", 12f, gold, true))

                    val recoveryTradeView = text(
                        recoveryStatusText(symbol), 12f, blue, true
                    )
                    tradeRecoveryStatusViews[symbol] = recoveryTradeView
                    detailsContainer.addView(recoveryTradeView)

                    detailsContainer.addView(space(6))
                    detailsContainer.addView(text(
                        "Contract: ${if (p.contractId > 0L) p.contractId.toString() else "Pending"}",
                        12f, Color.WHITE
                    ))
                    detailsContainer.addView(text(
                        "Stake: ${formatMoney(p.stake)}", 13f, gold
                    ))
                    detailsContainer.addView(space(5))
                    detailsContainer.addView(text(
                        entrySignalAuditText(p), 11.5f, Color.WHITE, true
                    ))

                    val statusView = text(
                        "Status: ${p.status}", 13f,
                        if (p.status == "OPEN") green else amber, true
                    )
                    tradeStatusViews[p.localId] = statusView
                    detailsContainer.addView(statusView)

                    if (p.contractId > 0L && p.status == "OPEN") {
                        detailsContainer.addView(space(6))
                        detailsContainer.addView(
                            actionButton("CLOSE THIS ${p.direction} TRADE", primary = false) {
                                AlertDialog.Builder(this)
                                    .setTitle("CONFIRM TRADE CLOSURE")
                                    .setMessage(
                                        "Are you sure you want to close this ${p.direction} contract on ${displayIndexName(p.symbol)}?"
                                    )
                                    .setNegativeButton("CANCEL", null)
                                    .setPositiveButton("CONFIRM CLOSE") { _, _ ->
                                        closeRunningPosition(p)
                                    }
                                    .show()
                            }
                        )
                    }
                }
