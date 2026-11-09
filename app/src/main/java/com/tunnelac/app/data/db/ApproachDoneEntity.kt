package com.tunnelac.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 접근도로 계산·저장을 마친 터널(받기 이어받기용 표시). 새 갱신을 시작할 때 비운다. */
@Entity(tableName = "approach_done")
data class ApproachDoneEntity(@PrimaryKey val tunnelId: Long)
