package com.kidzpos.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "stores")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Store {
    @Id
    private String id;
    @Column(nullable = false) private String name;
    private String location;
}
