package com.hermes.chatandroid.ui.kanban

import com.hermes.chatandroid.data.KanbanStatus

/** 보드를 처음 열 때 보이게 할 열. 실행 중이 있으면 그 열, 비었으면 대기. BoardOrder 는 바꾸지 않는다. */
fun initialBoardColumn(hasRunning: Boolean): KanbanStatus =
    if (hasRunning) KanbanStatus.RUNNING else KanbanStatus.READY
