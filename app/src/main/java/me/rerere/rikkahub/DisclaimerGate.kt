/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub

import me.rerere.rikkahub.data.datastore.Settings

internal enum class DisclaimerGateState(
    val rendersVisibleContent: Boolean,
    val allowsAcceptanceAction: Boolean,
    val exposesMainContent: Boolean,
) {
    LOADING(
        rendersVisibleContent = true,
        allowsAcceptanceAction = false,
        exposesMainContent = false,
    ),
    REQUIRES_ACCEPTANCE(
        rendersVisibleContent = true,
        allowsAcceptanceAction = true,
        exposesMainContent = false,
    ),
    ACCEPTED(
        rendersVisibleContent = true,
        allowsAcceptanceAction = false,
        exposesMainContent = true,
    ),
}

internal fun resolveDisclaimerGateState(settings: Settings): DisclaimerGateState = when {
    settings.init -> DisclaimerGateState.LOADING
    settings.disclaimerAccepted -> DisclaimerGateState.ACCEPTED
    else -> DisclaimerGateState.REQUIRES_ACCEPTANCE
}
