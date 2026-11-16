package com.hermes.chatandroid.data

/** 에이전트 칩이 멘션을 붙인 뒤의 글과 커서. */
data class MentionEdit(val text: String, val cursor: Int)

/**
 * 입력창 커서. BasicTextField 의 String 오버로드는 바깥에서 글을 바꾸면
 * 선택을 0에 남겨, 칩으로 넣은 @이름 앞으로 커서가 점프한다.
 */
object ComposerCaret {
    /**
     * 에이전트 칩 삽입. 빈 칸 뒤나 글 끝에 `@slug ` 를 붙이고,
     * 커서는 이름 뒤(끝 공백 다음)에 둔다.
     */
    fun insertMention(body: String, slug: String): MentionEdit {
        val text = if (body.isEmpty() || body.endsWith(" ")) "$body@$slug " else "$body @$slug "
        return MentionEdit(text, caretAfterExternalEdit(text))
    }

    /** 필드 밖에서 글을 바꾼 뒤의 커서. 칩은 이름을 뒤에 붙이므로 끝 = 이름 뒤. */
    fun caretAfterExternalEdit(text: String): Int = text.length
}
