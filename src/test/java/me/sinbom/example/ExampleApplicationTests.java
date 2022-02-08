package me.sinbom.example;

import me.sinbom.example.entity.Comments;
import me.sinbom.example.entity.Posts;
import me.sinbom.example.entity.Users;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertNull;

@Transactional
@ActiveProfiles(value = "test")
@SpringBootTest
class ExampleApplicationTests {

    @Autowired
    private EntityManager entityManager;

    @Test
    void 엔티티를_SoftDelete방식으로_삭제처리한다() {
        // given
        Users youngjin = new Users("yjsin@faai.co.kr", "신영진");
        Users gurim = new Users("gurim@faai.co.kr", "최규림");
        Users jengYoung = new Users("jengyoung@faai.co.kr", "황재영");
        Posts notice = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!", youngjin);
        Comments commentOfGurim = new Comments("우와아~ 집에 갑시다.", notice, gurim);
        Comments commentOfJengYoung = new Comments("노트북 가져가도 되나요?", notice, jengYoung);

        // when
        entityManager.persist(youngjin);
        entityManager.persist(gurim);
        entityManager.persist(jengYoung);
        entityManager.persist(notice);
        entityManager.persist(commentOfGurim);
        entityManager.persist(commentOfJengYoung);
        entityManager.flush();
        youngjin.delete();
        notice.delete();
        commentOfGurim.delete();
        commentOfJengYoung.delete();
        entityManager.flush();
        entityManager.clear();

        // then
        assertNull(entityManager.find(Users.class, youngjin.getId()));
        assertNull(entityManager.find(Posts.class, notice.getId()));
        assertNull(entityManager.find(Comments.class, commentOfGurim.getId()));
        assertNull(entityManager.find(Comments.class, commentOfJengYoung.getId()));
    }

    @Test
    void 엔티티의_상태를_removed로_변경하여_SoftDelete방식으로_Cascade삭제처리한다() {
        // given
        Users youngjin = new Users("yjsin@faai.co.kr", "신영진");
        Users gurim = new Users("gurim@faai.co.kr", "최규림");
        Users jengYoung = new Users("jengyoung@faai.co.kr", "황재영");
        Posts notice = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!", youngjin);
        Comments commentOfGurim = new Comments("우와아~ 집에 갑시다.", notice, gurim);
        Comments commentOfJengYoung = new Comments("노트북 가져가도 되나요?", notice, jengYoung);

        // when
        entityManager.persist(youngjin);
        entityManager.persist(gurim);
        entityManager.persist(jengYoung);
        entityManager.persist(notice);
        entityManager.persist(commentOfGurim);
        entityManager.persist(commentOfJengYoung);
        entityManager.flush();
        entityManager.remove(youngjin); // on delete cascade
        entityManager.flush();

        // then
        assertNull(entityManager.find(Users.class, youngjin.getId()));
        assertNull(entityManager.find(Posts.class, notice.getId()));
        assertNull(entityManager.find(Comments.class, commentOfGurim.getId()));
        assertNull(entityManager.find(Comments.class, commentOfJengYoung.getId()));
    }

}
