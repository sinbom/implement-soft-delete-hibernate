package me.sinbom.example;

import me.sinbom.example.entity.Comments;
import me.sinbom.example.entity.Posts;
import org.hibernate.Session;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceException;
import javax.persistence.Query;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
@ActiveProfiles(value = "test")
@SpringBootTest
class ExampleApplicationTests {

    @Autowired
    private EntityManager entityManager;

    @Test
    void softDelete() {
        // given
        Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");
        Comments comment = new Comments("우와아~ 집에 갑시다.", post);
        Comments comment2 = new Comments("노트북 가져가도 되나요?", post);

        // when
        entityManager.persist(post);
        entityManager.persist(comment);
        entityManager.persist(comment2);
        entityManager.flush();
        post.delete();
        comment.delete();
        comment2.delete();
        entityManager.flush();
        entityManager.clear();

        // then
        assertNull(entityManager.find(Posts.class, post.getId()));
        assertNull(entityManager.find(Comments.class, comment.getId()));
        assertNull(entityManager.find(Comments.class, comment2.getId()));
    }

    @Test
    void softDeleteCascade() {
        // given
        Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");
        Comments comment = new Comments("우와아~ 집에 갑시다.", post);
        Comments comment2 = new Comments("노트북 가져가도 되나요?", post);

        // when
        entityManager.persist(post);
        entityManager.persist(comment);
        entityManager.persist(comment2);
        entityManager.flush();
        entityManager.remove(post); // on soft delete cascade
        entityManager.flush();

        // then
        assertNull(entityManager.find(Posts.class, post.getId()));
        assertNull(entityManager.find(Comments.class, comment.getId()));
        assertNull(entityManager.find(Comments.class, comment2.getId()));
    }

    @Test
    void softDeleteUniqueIndex() {
        // given
        String sameTitle = "[FAAI] 공지사항";
        Posts post = new Posts(sameTitle, "오늘은 다들 일하지 말고 집에 가세요!");
        Posts post2 = new Posts(sameTitle, "오늘은 다들 일하지 말고 집에 가세요!");
        Posts post3 = new Posts(sameTitle, "오늘은 다들 일하지 말고 집에 가세요!");

        // when
        entityManager.persist(post);
        entityManager.flush();
        post.delete();
        entityManager.flush();
        entityManager.persist(post2);

        // then
        PersistenceException exception = assertThrows(
                PersistenceException.class,
                () -> {
                    entityManager.flush();
                    entityManager.persist(post3);
                }
        );
        assertEquals(ConstraintViolationException.class, exception.getCause().getClass());
    }

}
