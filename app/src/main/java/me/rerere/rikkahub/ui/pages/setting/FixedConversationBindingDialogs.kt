/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation

@Composable
internal fun ConversationBindingDialog(
    conversations: List<Conversation>,
    onSelect: (Conversation) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择绑定对话") },
        text = {
            if (conversations.isEmpty()) {
                Text("暂无可绑定的对话，请先创建专用对话")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(conversations, key = { it.id.toString() }) { conversation ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(conversation) }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(conversation.title.ifBlank { "未命名对话" })
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun AssistantBindingDialog(
    assistants: List<Assistant>,
    onSelect: (Assistant) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择助手") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(assistants, key = { it.id.toString() }) { assistant ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(assistant) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(assistant.name.ifBlank { "未命名助手" })
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
