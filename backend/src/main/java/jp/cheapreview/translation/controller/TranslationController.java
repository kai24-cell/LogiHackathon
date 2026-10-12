package jp.cheapreview.translation.controller;

import jp.cheapreview.translation.dto.TranslationDtos;
import jp.cheapreview.translation.service.TranslationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/translation")
public class TranslationController {
  private final TranslationService translations;

  public TranslationController(TranslationService translations) {
    this.translations = translations;
  }

  @GetMapping("/status")
  public TranslationDtos.Readiness status() {
    return translations.readiness();
  }
}
