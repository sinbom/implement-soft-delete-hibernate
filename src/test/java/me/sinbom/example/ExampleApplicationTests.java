package me.sinbom.example;

import me.sinbom.example.entity.Comments;
import me.sinbom.example.entity.Posts;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;
import javax.persistence.PersistenceException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
@ActiveProfiles(value = "test")
@SpringBootTest
class ExampleApplicationTests {

    @Autowired
    private EntityManager entityManager;

    @Test
    void SoftDelete를_사용한다() {
        // given
        Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");
        Comments comment = new Comments("우와아~ 집에 갑시다.", post);
        Comments comment2 = new Comments("노트북 가져가도 되나요?", post);

        // when
        entityManager.persist(post);
        entityManager.persist(comment);
        entityManager.persist(comment2);
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
    void SoftDelete에서_CascadeRemove를_사용한다() {
        // given
        Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");
        Comments comment = new Comments("우와아~ 집에 갑시다.", post);
        Comments comment2 = new Comments("노트북 가져가도 되나요?", post);

        // when
        entityManager.persist(post);
        entityManager.persist(comment);
        entityManager.persist(comment2);
        entityManager.remove(post); // on soft delete cascade
        entityManager.flush();

        // then
        List<Posts> result = entityManager
                .createQuery("SELECT p FROM Posts p LEFT JOIN FETCH p.comments", Posts.class)
                .getResultList();
        assertTrue(result.isEmpty());
    }

    @Test
    void SoftDelete에서_UniqueConstraint를_사용한다() {
        // given
        String sameTitle = "[FAAI] 공지사항";
        Posts post = new Posts(sameTitle, "오늘은 다들 일하지 말고 집에 가세요!");
        Posts post2 = new Posts(sameTitle, "오늘은 다들 일하지 말고 집에 가세요!");
        Posts post3 = new Posts(sameTitle, "오늘은 다들 일하지 말고 집에 가세요!");

        // when
        entityManager.persist(post);
        post.delete();
        entityManager.flush();
        entityManager.persist(post2);

        // then
        PersistenceException exception = assertThrows(
                PersistenceException.class,
                () -> entityManager.persist(post3)
        );
        assertEquals(ConstraintViolationException.class, exception.getCause().getClass());
    }

    @Test
    void 삭제처리된_부모엔티티를_패치조인으로_조회하면_삭제된_데이터가_정상조회되어_데이터_일관성_불일치가_발생한다() {
        // given
        Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");
        Comments comment = new Comments("우와아~ 집에 갑시다.", post);

        // when
        entityManager.persist(post);
        entityManager.persist(comment);
        post.delete();
        entityManager.flush();
        entityManager.clear();
        List<Comments> result = entityManager
                .createQuery("SELECT c FROM Comments c INNER JOIN FETCH c.post p", Comments.class)
                .getResultList();

        // then
        assertEquals(result.size(), 1);
        assertTrue(result.get(0).getPost().isDeleted());
    }

    @Test
    void 삭제처리된_부모엔티티를_지연로딩으로_조회하면_데이터_일관성_불일치로인해_에러가_발생한다() {
        // given
        Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");
        Comments comment = new Comments("우와아~ 집에 갑시다.", post);

        // when
        entityManager.persist(post);
        entityManager.persist(comment);
        post.delete();
        entityManager.flush();
        entityManager.clear();
        Comments comments = entityManager.find(Comments.class, comment.getId());

        // then
        assertThrows(
                EntityNotFoundException.class,
                () -> comments.getPost().getContent() // lazy loading & exception occurs
        );
    }

}
