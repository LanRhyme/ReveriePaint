/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reverie.paint.model.InlinePart
import com.reverie.paint.model.InlineStyleType
import com.reverie.paint.model.MarkdownBlock
import com.reverie.paint.model.MarkdownParser
import com.reverie.paint.ui.theme.Morandi
import com.reverie.paint.ui.theme.Theme

@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val colors = Theme.current
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> {
                    MarkdownHeader(block)
                }
                is MarkdownBlock.ListItem -> {
                    MarkdownListItem(block)
                }
                is MarkdownBlock.BlockQuote -> {
                    MarkdownBlockQuote(block)
                }
                is MarkdownBlock.CodeBlock -> {
                    MarkdownCodeBlock(block)
                }
                is MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = Morandi.subText.copy(alpha = 0.2f),
                        thickness = 1.dp,
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    MarkdownRichText(
                        text = block.text,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownHeader(block: MarkdownBlock.Header) {
    val colors = Theme.current
    when (block.level) {
        1 -> {
            Text(
                text = block.text,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
        }
        2 -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Morandi.accent.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = block.text,
                        color = Morandi.accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        3 -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(13.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Morandi.accent),
                )
                Text(
                    text = block.text,
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        else -> {
            Text(
                text = block.text,
                color = colors.text.copy(alpha = 0.9f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MarkdownListItem(block: MarkdownBlock.ListItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.indent * 12).dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (block.ordered) {
            Text(
                text = "${block.number ?: 1}.",
                color = Morandi.subText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .widthIn(min = 16.dp)
                    .padding(top = 1.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .padding(top = 6.5.dp, end = 8.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Morandi.accent),
            )
        }

        MarkdownRichText(
            text = block.text,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun MarkdownBlockQuote(block: MarkdownBlock.BlockQuote) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(Morandi.subText.copy(alpha = 0.45f)),
        )
        Spacer(Modifier.width(8.dp))
        MarkdownRichText(
            text = block.text,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MarkdownCodeBlock(block: MarkdownBlock.CodeBlock) {
    val colors = Theme.current
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Morandi.panel.copy(alpha = 0.7f))
            .padding(8.dp),
    ) {
        Text(
            text = block.code,
            color = colors.text.copy(alpha = 0.88f),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.horizontalScroll(hScroll),
        )
    }
}

@Composable
private fun MarkdownRichText(
    text: String,
    modifier: Modifier = Modifier,
    fontStyle: FontStyle = FontStyle.Normal,
) {
    val colors = Theme.current
    val parts = remember(text) { MarkdownParser.parseInline(text) }

    val linkStyles = remember {
        TextLinkStyles(
            style = SpanStyle(
                color = Morandi.accent,
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Medium,
            )
        )
    }

    val annotatedString = remember(parts, colors) {
        buildAnnotatedString {
            parts.forEach { part ->
                when (part.type) {
                    InlineStyleType.BOLD -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.text)) {
                            append(part.text)
                        }
                    }
                    InlineStyleType.ITALIC -> {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(part.text)
                        }
                    }
                    InlineStyleType.BOLD_ITALIC -> {
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.Bold,
                                fontStyle = FontStyle.Italic,
                                color = colors.text,
                            )
                        ) {
                            append(part.text)
                        }
                    }
                    InlineStyleType.CODE -> {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = Morandi.accent,
                                background = Morandi.panel.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                            )
                        ) {
                            append(" ${part.text} ")
                        }
                    }
                    InlineStyleType.STRIKE -> {
                        withStyle(
                            SpanStyle(
                                textDecoration = TextDecoration.LineThrough,
                                color = Morandi.subText,
                            )
                        ) {
                            append(part.text)
                        }
                    }
                    InlineStyleType.LINK -> {
                        if (!part.linkUrl.isNullOrBlank()) {
                            withLink(LinkAnnotation.Url(url = part.linkUrl, styles = linkStyles)) {
                                append(part.text)
                            }
                        } else {
                            append(part.text)
                        }
                    }
                    null -> {
                        append(part.text)
                    }
                }
            }
        }
    }

    Text(
        text = annotatedString,
        color = colors.text.copy(alpha = 0.82f),
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontStyle = fontStyle,
        modifier = modifier,
    )
}
