package me.sinbom.example;

import me.sinbom.example.entity.Comments;
import me.sinbom.example.entity.Posts;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.persistence.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
@ActiveProfiles(value = "test")
@SpringBootTest
class SoftDeleteTests {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    ExecutorService executorService = Executors.newFixedThreadPool(2);

    @BeforeEach
    void init() {
        entityManager
                .createQuery("DELETE FROM Comments")
                .executeUpdate();
        entityManager
                .createQuery("DELETE FROM Posts")
                .executeUpdate();
    }

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
                .createQuery("SELECT p FROM Posts p LEFT JOIN FETCH p.comments c", Posts.class)
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
        Comments find = entityManager.find(Comments.class, comment.getId());

        // then
        assertThrows(
                EntityNotFoundException.class,
                () -> find.getPost().getContent() // lazy loading & exception occurs
        );
    }

    @Test
    void 삭제처리된_프록시엔티티를_매핑하면_데이터_일관성_불일치가_발생한다() {
        // given
        Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");

        // when
        entityManager.persist(post);
        post.delete();
        entityManager.flush();
        Posts deletedPost = entityManager.getReference(Posts.class, post.getId());
        Comments comment = new Comments("우와아~ 집에 갑시다.", deletedPost);
        entityManager.persist(comment);
        entityManager.clear();
        Comments find = entityManager.find(Comments.class, comment.getId());

        // then
        assertThrows(
                EntityNotFoundException.class,
                () -> find.getPost().getContent() // lazy loading & exception occurs
        );
    }

    @Test
    void 트랜잭션_경합조건에_따라_삭제처리된_데이터를_매핑하여_데이터_일관성_불일치가_발생한다() {
        // given
        EntityManager em = entityManagerFactory.createEntityManager();
        Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");

        em.getTransaction().begin();
        em.persist(post);
        em.getTransaction().commit();

        // when
        // tx1 start
        EntityManager em1 = entityManagerFactory.createEntityManager();
        EntityTransaction tx1 = em1.getTransaction();
        tx1.begin();

        Posts postTx1 = em1.find(Posts.class, post.getId());

        if (CollectionUtils.isEmpty(postTx1.getComments())) {
            postTx1.delete();
        }

        // tx2 start
        EntityManager em2 = entityManagerFactory.createEntityManager();
        EntityTransaction tx2 = em2.getTransaction();

        tx2.begin();

        Posts postTx2 = em2.find(Posts.class, post.getId());
        Comments commentTx2 = new Comments("우와아~ 집에 갑시다.", postTx2);

        em2.persist(commentTx2);
        tx2.commit();
        // tx2 end

        tx1.commit();
        // tx1 end

        Comments comment = this.entityManager.find(Comments.class, commentTx2.getId());

        // then
        assertThrows(
                EntityNotFoundException.class,
                () -> comment.getPost().getContent() // lazy loading & exception occurs
        );
    }

    @Test // TODO
    void 트랜잭션_경합조건에_따라_삭제처리된_데이터를_매핑할수_없도록_낙관적락으로_방지한다() throws Exception {
        // given
        CountDownLatch awaitForFindPost = new CountDownLatch(1);
        CountDownLatch awaitForAllCommit = new CountDownLatch(2);

        EntityManager entityManager = entityManagerFactory.createEntityManager();
        entityManager.getTransaction().begin();

        Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");
        entityManager.persist(post);

        entityManager.getTransaction().commit();
        entityManager.clear();

        // when
        Runnable deletePost = () -> {
            EntityManager em = entityManagerFactory.createEntityManager();
            em.getTransaction().begin();

            Posts find = em.find(Posts.class, post.getId());
            find.delete();

            try {
                awaitForFindPost.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            em.getTransaction().commit();
            awaitForAllCommit.countDown();
        };

        Runnable insertComment = () -> {
            EntityManager em = entityManagerFactory.createEntityManager();
            em.getTransaction().begin();

            Posts find = em.find(Posts.class, post.getId(), LockModeType.OPTIMISTIC_FORCE_INCREMENT);
            Comments comment = new Comments("우와아~ 집에 갑시다.", find);
            awaitForFindPost.countDown();

            em.persist(comment);
            try {
                em.getTransaction().commit();
            } finally {
                awaitForAllCommit.countDown();
            }
        };

        executorService.execute(deletePost);

        // then
        ExecutionException executionException = assertThrows(
                ExecutionException.class,
                () -> executorService.submit(insertComment).get()
        );
        assertTrue(executionException.getCause().getCause() instanceof OptimisticLockException);

        awaitForAllCommit.await();
    }

    @Test // TODO
    void 트랜잭션_경합조건에_따라_삭제처리된_데이터를_매핑할수_없도록_비관적락으로_방지한다() throws Exception {
        // given
        EntityManager em = entityManagerFactory.createEntityManager();
        Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");

        em.getTransaction().begin();
        em.persist(post);
        em.getTransaction().commit();

        // when
        // tx1 start
        EntityManager em1 = entityManagerFactory.createEntityManager();
        EntityTransaction tx1 = em1.getTransaction();
        tx1.begin();

        Posts postTx1 = em1.find(Posts.class, post.getId());

        if (CollectionUtils.isEmpty(postTx1.getComments())) {
            postTx1.delete();
        }

        // tx2 start
        EntityManager em2 = entityManagerFactory.createEntityManager();
        EntityTransaction tx2 = em2.getTransaction();

        tx2.begin();

        Posts postTx2 = em2.find(Posts.class, post.getId());
        Comments commentTx2 = new Comments("우와아~ 집에 갑시다.", postTx2);

        em2.persist(commentTx2);
        tx2.commit();
        // tx2 end

        tx1.commit();
        // tx1 end

        Comments comment = this.entityManager.find(Comments.class, commentTx2.getId());

        // then
        assertThrows(
                EntityNotFoundException.class,
                () -> comment.getPost().getContent() // lazy loading & exception occurs
        );
    }

}
