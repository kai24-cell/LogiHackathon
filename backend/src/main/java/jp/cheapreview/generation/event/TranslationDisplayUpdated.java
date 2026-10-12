package jp.cheapreview.generation.event;

import jp.cheapreview.generation.dto.GenerationDtos.Answer;

/** 翻訳結果の表示更新を会話等へ通知する。英語原文や履歴の再commitはしない。全失敗も未翻訳として通知する。 */
public record TranslationDisplayUpdated(String jobId, Answer display, boolean hasUntranslated) {}
