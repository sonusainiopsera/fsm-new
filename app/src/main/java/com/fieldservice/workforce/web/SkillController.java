package com.fieldservice.workforce.web;

import com.fieldservice.platform.pagination.PageLinks;
import com.fieldservice.platform.pagination.PageMeta;
import com.fieldservice.platform.pagination.PagedResponse;
import com.fieldservice.workforce.internal.WorkforceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/skills")
public class SkillController {

    private final WorkforceService workforceService;

    public SkillController(WorkforceService workforceService) {
        this.workforceService = workforceService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<SkillResponse>> listSkills(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        int cappedSize = Math.min(Math.max(size, 1), 50);
        List<SkillResponse> all = workforceService.listSkills(activeOnly);
        int total = all.size();
        int fromIdx = Math.min(page * cappedSize, total);
        int toIdx   = Math.min(fromIdx + cappedSize, total);
        List<SkillResponse> data = all.subList(fromIdx, toIdx);
        PageMeta meta = PageMeta.of(page, cappedSize, total);
        String nextLink = toIdx < total ? "/api/v1/skills?page=" + (page + 1) + "&size=" + cappedSize : null;
        String prevLink = page > 0      ? "/api/v1/skills?page=" + (page - 1) + "&size=" + cappedSize : null;
        return ResponseEntity.ok(PagedResponse.of(data, meta, PageLinks.of(nextLink, prevLink)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SkillResponse createSkill(@Valid @RequestBody SkillRequest request) {
        return workforceService.createSkill(request);
    }

    @DeleteMapping("/{code}")
    public SkillResponse deactivateSkill(@PathVariable String code) {
        return workforceService.deactivateSkill(code);
    }
}
