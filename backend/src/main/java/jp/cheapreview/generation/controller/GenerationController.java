package jp.cheapreview.generation.controller;

import jp.cheapreview.generation.dto.GenerationDtos;
import jp.cheapreview.generation.dto.SettingsDtos;
import jp.cheapreview.generation.service.GenerationService;
import jp.cheapreview.generation.service.GenerationSettings;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GenerationController {
  private final GenerationService generation;
  private final GenerationSettings settings;

  public GenerationController(GenerationService generation, GenerationSettings settings) {
    this.generation = generation;
    this.settings = settings;
  }

  @GetMapping("/settings")
  public SettingsDtos.Status settings() {
    return settings.status();
  }

  @PutMapping("/settings")
  public SettingsDtos.Status configure(@RequestBody SettingsDtos.Update request) {
    return settings.update(request);
  }

  @DeleteMapping("/settings")
  public SettingsDtos.Status clear() {
    settings.clear();
    return settings.status();
  }

  @PostMapping("/analysis/jobs")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public GenerationDtos.Started start(@RequestBody GenerationDtos.Start request) {
    return generation.start(request);
  }

  @GetMapping("/jobs/{id}")
  public GenerationDtos.Job get(@PathVariable String id) {
    return generation.query(id);
  }

  @GetMapping("/analysis/jobs/active")
  public GenerationDtos.Started activeJob() {
    return generation.activeJob();
  }

  @PostMapping("/jobs/{id}/cancel")
  public GenerationDtos.Job cancel(@PathVariable String id) {
    return generation.cancel(id);
  }
}
