package com.kidzpos.web;

import com.kidzpos.domain.Store;
import com.kidzpos.dto.Dtos.StoreReq;
import com.kidzpos.repo.StoreRepository;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
public class StoreController {
    private final StoreRepository repo;
    public StoreController(StoreRepository repo) { this.repo = repo; }

    @GetMapping public List<Store> list() { return repo.findAll(); }

    @PostMapping public Store create(@Valid @RequestBody StoreReq r) {
        return repo.save(Store.builder().id(r.id()).name(r.name()).location(r.location()).build());
    }

    @PutMapping("/{id}") public Store update(@PathVariable String id, @Valid @RequestBody StoreReq r) {
        var s = repo.findById(id).orElseThrow();
        s.setName(r.name()); s.setLocation(r.location());
        return repo.save(s);
    }

    @DeleteMapping("/{id}") public void delete(@PathVariable String id) { repo.deleteById(id); }
}
