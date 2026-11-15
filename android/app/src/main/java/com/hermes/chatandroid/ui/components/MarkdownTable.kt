package com.hermes.chatandroid.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** 셀 하나의 최대 폭 — 넘으면 줄바꿈. 표 전체가 말풍선보다 넓으면 가로 스크롤. */
private val MaxCellWidth = 220.dp

/**
 * 마크다운 표(T-177). 열 폭 = 그 열 셀들의 최대 고유 폭(최대 [MaxCellWidth]),
 * 행 높이 = 그 행 셀들의 최대 높이로 맞춰 격자가 어긋나지 않게 한다.
 */
@Composable
fun MarkdownTable(
    table: MdBlock.Table,
    textColor: Color,
    borderColor: Color,
    headerBackground: Color,
    codeBackground: Color,
    linkColor: Color,
) {
    val cols = table.header.size
    val allRows = listOf(table.header) + table.rows
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .horizontalScroll(rememberScrollState())
            .clip(shape)
            .border(1.dp, borderColor, shape),
    ) {
        Layout(
            content = {
                allRows.forEachIndexed { r, row ->
                    row.forEach { cell ->
                        Box(
                            Modifier
                                .then(if (r == 0) Modifier.background(headerBackground) else Modifier)
                                .border(0.5.dp, borderColor)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        ) {
                            Text(
                                inlineMarkdown(cell, codeBackground = codeBackground, linkColor = linkColor),
                                color = textColor,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (r == 0) FontWeight.Bold else null,
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val cap = MaxCellWidth.roundToPx()
            val rowCount = allRows.size
            val widths = IntArray(cols)
            for (i in measurables.indices) {
                val c = i % cols
                widths[c] = maxOf(widths[c], minOf(measurables[i].maxIntrinsicWidth(Constraints.Infinity), cap))
            }
            val heights = IntArray(rowCount)
            for (i in measurables.indices) {
                val r = i / cols
                heights[r] = maxOf(heights[r], measurables[i].minIntrinsicHeight(widths[i % cols]))
            }
            val placeables = measurables.mapIndexed { i, m ->
                m.measure(Constraints.fixed(widths[i % cols], heights[i / cols]))
            }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                for (r in 0 until rowCount) {
                    var x = 0
                    for (c in 0 until cols) {
                        placeables[r * cols + c].place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}
