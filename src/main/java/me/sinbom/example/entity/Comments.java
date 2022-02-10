package me.sinbom.example.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import javax.persistence.*;

@Entity
@Where(clause = "deleted = false")
@SQLDelete(sql = "UPDATE comments SET deleted = true WHERE id = ?")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comments {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String content;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Posts post;

    @Column(nullable = false)
    private boolean deleted;

    public Comments(String content, Posts post) {
        this.content = content;
        this.post = post;
        this.post.addComment(this);
    }

    public void delete() {
        this.deleted = true;
    }

}
