# JPA + Hibernate 기반의 개발 환경에서 Soft Delete 구현하기

## Soft Delete란?

`Soft Delete` 또는 `Logical Delete`는 delete 쿼리를 사용하여 물리적으로 데이터를 삭제하는 것이 아니라
update 쿼리를 통해 상태를 변경하여 삭제된 데이터로 구분할 수 있도록 논리적으로 데이터를 삭제하는 것을 의미합니다.

삭제된 데이터로 구분할 수 있는 방법은 다음과 같습니다.
- `delete_flag(boolean, int)` 컬럼의 값이 true 또는 1인 경우
- `delete_at(timestamp)` 컬럼의 값이 현재 timestamp보다 이전인 경우

`Soft Delete`는 물리적인 데이터 삭제가 부담되거나 삭제된 데이터들을 보관하여 데이터로써 활용할 필요나 가치가 있는 경우에 사용됩니다.

## Soft Delete & Hard Delete 비교

`Soft Delete`와 `Hard Delete`를 여러가지 관점에서 비교해보겠습니다.

|                   | Soft Delete                                                   | Hard Delete                       | comment                                                                                          |
|-------------------|---------------------------------------------------------------|-----------------------------------|--------------------------------------------------------------------------------------------------|
| 삭제                | UPDATE table SET delete_flag = true WHERE id = ?              | DELETE table FROM WHERE id = ?    | Soft Delete는 삭제 구분 값을 수정하여 논리적으로 데이터를 삭제 처리합니다.                                                  |
| 조회                | SELECT * FROM table WHERE delete_flag = false                 | SELECT * FROM table               | Soft Delete는 모든 조회 쿼리에 delete_flag = false 조건이 필요합니다.                                            |
| 복원                | update 쿼리로 삭제 구분 값을 변경하여 복원합니다.                               | 백업 또는 쿼리 로그를 통해 복원합니다.            | Hard Delete는 백업과 장애 발생 시점의 간격과 쿼리 로그 유무에 따라 복원이 어려울 수 있습니다.                                      |
| 디스크 사용량           | 삭제시 테이블의 디스크 사용량이 감소하지 않습니다.                                  | 삭제시 테이블의 디스크 사용량이 감소합니다.          | Soft Delete는 지속적으로 테이블의 디스크 사용량이 증가합니다.                                                          |
| unique constraint | 삭제 처리된 값을 필터링 할 수 있는 partial index를 사용하여 unique index를 생성합니다. | 중복될 수 없는 값들로 unique index를 구성합니다. | Sofe Delete는 partial index를 사용하거나 지원하지 않는 DBMS는 삭제 구분 컬럼으로 timestamp를 사용하고 index 컬럼에 포함하여 사용합니다. |
| on delete cascade | 물리적인 삭제가 발생하지 않기 때문에 사용할 수 없습니다.                              | 삭제시 설정된 cascade가 발생합니다.           | Soft Delete는 삭제 처리시 발생하는 cascade를 애플리케이션 레벨에서 또는 데이터베이스 트리거로 직접 구현해야합니다.                         |

`Soft Delete` 방식을 사용할 때 큰 단점은 바로 테이블과 인덱스에서 삭제처리된 데이터가 제거되지 않는다는 점입니다. 데이터가 물리적으로 삭제되지 않기 때문에 지속적으로 디스크 사용량이 증가하고 데이터를 조회하기 위해 삭제처리된 데이터까지 함께 조회하기 때문에 조회 성능이 저하될 수 있습니다.

```postgresql
CREATE TABLE foo
(
	deleted BOOLEAN NOT NULL,
	name varchar(255) NOT  NULL
);

CREATE INDEX FOO_NAME_INDEX ON foo(name);

do $$
    begin
        for i in 1..100000 loop
                INSERT INTO foo (name, deleted) VALUES (CONCAT('bar ', i), false);
            end loop;
    end;
$$;

VACUUM FULL foo;

SELECT
    pg_size_pretty(pg_total_relation_size('foo')) AS "총 용량",
    pg_size_pretty(pg_relation_size('foo')) AS "테이블 용량",
    pg_size_pretty(pg_indexes_size('foo')) AS "인덱스 용량";
```

```text
  총 용량  |  테이블 용량   |  인덱스 용량
---------+-------------+-------------
 7416 kB |   4328 kB   |  3088 kB
```

삭제 구분 컬럼과 이름 필드가 존재하는 테이블을 생성하고 이름 필드에 인덱스를 생성합니다. 100000개의 테스트 데이터를 insert하고
테이블과 인덱스의 디스크 사용량을 확인하기 위해 VACUUM, REINDEX 명령어를 실행하여 변경 또는 삭제된 데이터들이 차지 하고있는 디스크 공간을 확보 후 용량을 확인합니다.

- VACCUM : 변경 또는 삭제 처리된 데이터들이 차지하고 있는 테이블의 디스크 공간을 확보합니다.
- REBUILD INDEX : 변경 또는 삭제 처리된 인덱스 트리의 노드들을 제외한 인덱스를 재색인하여 디스크 공간을 확보하고 삭제된 노드들로 인해 증가한 노드의 물리적인 접근 횟수가 감소합니다.

```postgresql
DELETE FROM foo;

REINDEX INDEX foo_name_index;
VACUUM FULL foo;
```

```text
  총 용량     |  테이블 용량   |  인덱스 용량
------------+-------------+-------------
 8192 bytes |   0 bytes   | 8192 bytes
```

`Hard Delete` 방식은 물리적으로 데이터를 삭제하여 테이블과 인덱스의 디스크 사용량이 감소하였습니다.

```postgresql
UPDATE foo SET deleted = true;

REINDEX INDEX foo_name_index;
VACUUM FULL foo;
```

```text
  총 용량  |  테이블 용량   |  인덱스 용량
---------+-------------+-------------
 7416 kB |   4328 kB   |  3088 kB
```

`Sofe Delete` 방식은 논리적으로 데이터를 삭제하여 테이블과 인덱스의 디스크 사용량에 변화가 없기 때문에 지속적으로 사용량이 증가합니다.

```postgresql
CREATE UNIQUE INDEX UK_FOO_NAME_INDEX ON foo(name) WHERE deleted = false;

UPDATE foo SET deleted = true;

REINDEX INDEX uk_foo_name_index;
VACUUM FULL foo;
```

```text
  총 용량  |  테이블 용량   |  인덱스 용량
---------+-------------+-------------
 4336 kB |   4328 kB   |  8192 bytes
```

`partial index`를 사용하면 논리적으로 삭제 처리된 데이터가 인덱스에서 필터링되어 인덱스의 사용량이 감소한다는 것을 확인할 수 있습니다. 
삭제된 데이터가 인덱스 트리에서 제거되어 논리적인 접근 횟수와 물리적인 접근 횟수가 동일해지므로 일반 index를 사용하는 것보다 조회 쿼리의 성능에 더 좋은 영향을 줄 수 있습니다.

그렇다면 `partial index`는 모든 쿼리에 적용될 수 있을까요?

```postgresql
EXPLAIN ANALYZE SELECT * FROM foo WHERE name = 'bar 99' AND deleted = false; -- (1)
                                                QUERY PLAN
------------------------------------------------------------------------------------------------------------------------
Index Scan using uk_foo_name_index on foo  (cost=0.42..8.44 rows=1 width=18) (actual time=0.303..0.304 rows=0 loops=1)
  Index Cond: ((name)::text = 'ba 99'::text)
Planning Time: 1.463 ms
Execution Time: 0.319 ms
(4 rows)

EXPLAIN ANALYZE SELECT * FROM foo WHERE name = 'bar 99' AND deleted = true; -- (2)
                                                QUERY PLAN
------------------------------------------------------------------------------------------------------------------------
Seq Scan on foo  (cost=0.00..1887.00 rows=1 width=18) (actual time=11.165..11.166 rows=0 loops=1)
  Filter: (deleted AND ((name)::text = 'ba 99'::text))
  Rows Removed by Filter: 100000
Planning Time: 0.103 ms
Execution Time: 11.190 ms
(5 rows)

EXPLAIN ANALYZE SELECT * FROM foo WHERE name = 'bar 99' AND deleted != false; -- (3)
                                                QUERY PLAN
------------------------------------------------------------------------------------------------------------------------
Seq Scan on foo  (cost=0.00..1887.00 rows=1 width=18) (actual time=11.825..11.826 rows=0 loops=1)
  Filter: (deleted AND ((name)::text = 'ba 99'::text))
  Rows Removed by Filter: 100000
Planning Time: 0.075 ms
Execution Time: 11.851 ms
(5 rows)

EXPLAIN ANALYZE SELECT * FROM foo WHERE name = 'bar 99' AND deleted IS NOT false; -- (4)
                                                QUERY PLAN
------------------------------------------------------------------------------------------------------------------------    
Seq Scan on foo  (cost=0.00..1887.00 rows=1 width=18) (actual time=11.669..11.671 rows=0 loops=1)
  Filter: ((deleted IS NOT FALSE) AND ((name)::text = 'ba 99'::text))
  Rows Removed by Filter: 100000
Planning Time: 0.072 ms
Execution Time: 11.694 ms
(5 rows)

EXPLAIN ANALYZE SELECT * FROM foo WHERE name = 'bar 99'; -- (5)
                                                QUERY PLAN
------------------------------------------------------------------------------------------------------------------------
Seq Scan on foo  (cost=0.00..1887.00 rows=1 width=18) (actual time=12.271..12.272 rows=0 loops=1)
  Filter: ((name)::text = 'ba 99'::text)
  Rows Removed by Filter: 100000
Planning Time: 0.069 ms
Execution Time: 12.289 ms
(5 rows)
```

어떤 조회 쿼리가 partial index가 적용될 수 있는지 각 쿼리의 실행계획을 확인해본 결과, 필터링 조건과 완전히 동일한 (1)번의 조회 쿼리를 제외한 모든 쿼리의 실행계획은 sequential scan이 선택되었습니다.
`partial index`는 반드시 완전히 동일한 조건이 포함된 쿼리에만 적용된다는 것을 알 수 있습니다.

> 테이블의 용량이 매우 작거나 거의 모든 테이블을 조회하는 경우 index를 통해 data block에 접근하는 것보다 sequential scan을 사용하는 것이 더 빠르기 때문에 optimizer가 index scan을 사용하지 않을 수도 있습니다.
> 

## JPA + Hibernate 개발 환경에서의 구현

Spring Boot 기반의 웹 애플리케이션을 Hibernate를 사용해서 개발할 때 soft delete를 구현하는 방법과 주의해야할 점에 대해서 알아보겠습니다.

개발 환경 정보
- Spring Boot 2.5.10
- Hibernate 5.4.33
- Postgresql 14.1

### 구현

```java
@Entity
@Where(clause = "deleted = false")
@SQLDelete(sql = "UPDATE posts SET deleted = true WHERE id = ?")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Posts {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private boolean deleted;

    @OneToMany(fetch = FetchType.LAZY, mappedBy = "post", cascade = CascadeType.REMOVE)
    private List<Comments> comments = new ArrayList<>();

    public Posts(String title, String content) {
        this.title = title;
        this.content = content;
    }

    public void delete() {
        this.deleted = true;
    }

    public void addComment(Comments comment) {
        this.comments.add(comment);
    }

}
```

```java
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
```

예제 소스 코드에서 사용할 게시글과 댓글 엔티티 클래스입니다. soft delete를 구현하기 위해 boolean타입의 deleted 필드로 삭제 유무를 구분하고 delete 메소드를 통해 객체의 상태를 변경하여 삭제합니다.

```java
@Where(clause = "deleted = false")
```
@Where 애노테이션이 지정된 엔티티를 조회할 때 쿼리의 where절에 반드시 포함되는 조건을 설정할 수 있습니다. 삭제 구분 컬럼은 sofe delete에서
삭제된 데이터를 제외하기 위해서 반드시 포함되어야하지만 개발자가 실수로 조건절에서 누락할 수 있기 때문에 애노테이션을 통해 글로벌하게 설정하는 것이 좋습니다. 또한 연관관계 엔티티의 패치 타입 전략을 Lazy하게 가져가는 경우
Lazy Loading으로 발생하는 조회 쿼리의 조건절에도 포함시키기 위해서는 반드시 사용해야합니다. 하지만 JPQL 또는 HQL이 아닌 Native SQL을 사용할 때는 적용되지 않기 때문에 주의해야합니다.

```java
@SQLDelete(sql = "UPDATE comments SET deleted = true WHERE id = ?")
```

@SQLDelete 애노테이션이 지정된 엔티티의 상태를 removed로 변경할 때 발생하는 쿼리를 설정할 수 있습니다. soft delete는 delete 쿼리가 발생하지 않기 때문에
on delete cascade를 사용할 수 없지만 @SQLDelete와 cascade 옵션을 함께 사용하면 soft delete에서 cascade를 별도의 데이터베이스 트리거나 소스 코드 없이도 쉽게 구현할 수 있습니다.

```postgresql
CREATE UNIQUE INDEX IF NOT EXISTS UK_POSTS_TITLE_INDEX ON posts(title) WHERE deleted = false;
```

unique constraint를 적용해야하는 경우 사용하고 있는 DBMS가 partial index를 지원한다면 삭제된 데이터를 인덱스에서 필터링하여 제거된 데이터를 제외합니다. partial index는 JPA 스펙에 포함되어 있지 않기 때문에
애노테이션 기반으로 생성할 수 없습니다.

```yaml
spring:
  sql:
    init:
      mode: always
      
logging:
  level:
    springframework:
      jdbc:
        datasource:
          init:
            ScriptUtils: DEBUG
```

인덱스 생성 쿼리를 schema.sql 파일에 작성하고 classpath에 위치합니다. spring.sql.init.mode 프로퍼티를 always로 설정하여
애플리케이션 실행시 sql script로 초기화하도록 설정합니다. 초기화 script 파일에서 발생한 쿼리 로그를 출력혀려면 로깅 레벨 설정이 필요합니다.

### 테스트

```java
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
```

```text
2022-02-13 23:27:44.677 DEBUG 4209 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        posts
        (content, deleted, title) 
    values
        (?, ?, ?)
2022-02-13 23:27:44.679 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-13 23:27:44.680 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-13 23:27:44.680 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-13 23:27:44.703 DEBUG 4209 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        comments
        (content, deleted, post_id) 
    values
        (?, ?, ?)
2022-02-13 23:27:44.703 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [우와아~ 집에 갑시다.]
2022-02-13 23:27:44.703 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-13 23:27:44.704 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [BIGINT] - [1]
2022-02-13 23:27:44.729 DEBUG 4209 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        comments
        (content, deleted, post_id) 
    values
        (?, ?, ?)
2022-02-13 23:27:44.730 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [노트북 가져가도 되나요?]
2022-02-13 23:27:44.730 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-13 23:27:44.731 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [BIGINT] - [1]
2022-02-13 23:27:44.743 DEBUG 4209 --- [    Test worker] org.hibernate.SQL                        : 
    update
        posts 
    set
        content=?,
        deleted=?,
        title=? 
    where
        id=?
2022-02-13 23:27:44.744 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-13 23:27:44.744 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [true]
2022-02-13 23:27:44.744 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-13 23:27:44.744 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [4] as [BIGINT] - [1]
2022-02-13 23:27:44.748 DEBUG 4209 --- [    Test worker] org.hibernate.SQL                        : 
    update
        comments 
    set
        content=?,
        deleted=?,
        post_id=? 
    where
        id=?
2022-02-13 23:27:44.748 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [우와아~ 집에 갑시다.]
2022-02-13 23:27:44.749 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [true]
2022-02-13 23:27:44.749 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [BIGINT] - [1]
2022-02-13 23:27:44.749 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [4] as [BIGINT] - [1]
2022-02-13 23:27:44.758 DEBUG 4209 --- [    Test worker] org.hibernate.SQL                        : 
    update
        comments 
    set
        content=?,
        deleted=?,
        post_id=? 
    where
        id=?
2022-02-13 23:27:44.758 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [노트북 가져가도 되나요?]
2022-02-13 23:27:44.758 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [true]
2022-02-13 23:27:44.758 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [BIGINT] - [1]
2022-02-13 23:27:44.758 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [4] as [BIGINT] - [2]
2022-02-13 23:27:44.768 DEBUG 4209 --- [    Test worker] org.hibernate.SQL                        : 
    select
        posts0_.id as id1_1_0_,
        posts0_.content as content2_1_0_,
        posts0_.deleted as deleted3_1_0_,
        posts0_.title as title4_1_0_ 
    from
        posts posts0_ 
    where
        posts0_.id=? 
        and (
            posts0_.deleted = false
        )
2022-02-13 23:27:44.769 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [BIGINT] - [1]
2022-02-13 23:27:44.777 DEBUG 4209 --- [    Test worker] org.hibernate.SQL                        : 
    select
        comments0_.id as id1_0_0_,
        comments0_.content as content2_0_0_,
        comments0_.deleted as deleted3_0_0_,
        comments0_.post_id as post_id4_0_0_ 
    from
        comments comments0_ 
    where
        comments0_.id=? 
        and (
            comments0_.deleted = false
        )
2022-02-13 23:27:44.777 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [BIGINT] - [1]
2022-02-13 23:27:44.780 DEBUG 4209 --- [    Test worker] org.hibernate.SQL                        : 
    select
        comments0_.id as id1_0_0_,
        comments0_.content as content2_0_0_,
        comments0_.deleted as deleted3_0_0_,
        comments0_.post_id as post_id4_0_0_ 
    from
        comments comments0_ 
    where
        comments0_.id=? 
        and (
            comments0_.deleted = false
        )
2022-02-13 23:27:44.780 TRACE 4209 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [BIGINT] - [2]
```

엔티티 객체의 deleted 필드 값을 true로 변경하여 삭제합니다. select 쿼리에 delete = false 조건이 where절에 포함되어 삭제된 데이터를 제외하는 것을 확인할 수 있습니다.

```java
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
```

```text
2022-02-13 23:29:21.475 DEBUG 4223 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        posts
        (content, deleted, title) 
    values
        (?, ?, ?)
2022-02-13 23:29:21.477 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-13 23:29:21.477 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-13 23:29:21.478 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-13 23:29:21.500 DEBUG 4223 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        comments
        (content, deleted, post_id) 
    values
        (?, ?, ?)
2022-02-13 23:29:21.500 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [우와아~ 집에 갑시다.]
2022-02-13 23:29:21.500 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-13 23:29:21.500 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [BIGINT] - [1]
2022-02-13 23:29:21.527 DEBUG 4223 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        comments
        (content, deleted, post_id) 
    values
        (?, ?, ?)
2022-02-13 23:29:21.528 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [노트북 가져가도 되나요?]
2022-02-13 23:29:21.528 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-13 23:29:21.528 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [BIGINT] - [1]
2022-02-13 23:29:21.539 DEBUG 4223 --- [    Test worker] org.hibernate.SQL                        : 
    UPDATE
        comments 
    SET
        deleted = true 
    WHERE
        id = ?
2022-02-13 23:29:21.539 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [BIGINT] - [1]
2022-02-13 23:29:21.545 DEBUG 4223 --- [    Test worker] org.hibernate.SQL                        : 
    UPDATE
        comments 
    SET
        deleted = true 
    WHERE
        id = ?
2022-02-13 23:29:21.546 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [BIGINT] - [2]
2022-02-13 23:29:21.548 DEBUG 4223 --- [    Test worker] org.hibernate.SQL                        : 
    UPDATE
        posts 
    SET
        deleted = true 
    WHERE
        id = ?
2022-02-13 23:29:21.548 TRACE 4223 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [BIGINT] - [1]
2022-02-13 23:29:21.594 DEBUG 4223 --- [    Test worker] org.hibernate.SQL                        : 
    select
        posts0_.id as id1_1_0_,
        comments1_.id as id1_0_1_,
        posts0_.content as content2_1_0_,
        posts0_.deleted as deleted3_1_0_,
        posts0_.title as title4_1_0_,
        comments1_.content as content2_0_1_,
        comments1_.deleted as deleted3_0_1_,
        comments1_.post_id as post_id4_0_1_,
        comments1_.post_id as post_id4_0_0__,
        comments1_.id as id1_0_0__ 
    from
        posts posts0_ 
    left outer join
        comments comments1_ 
            on posts0_.id=comments1_.post_id 
            and (
                comments1_.deleted = false
            ) 
    where
        (
            posts0_.deleted = false
        )
```

엔티티의 상태를 removed로 변경하여 cascade remove 옵션이 적용된 연관관계 엔티티를 대상으로 @SQLDelete 애노테이션에 설정된 쿼리를 발생시켜 모두 삭제합니다.
조인을 사용하는 select 쿼리에 deleted = false 조건이 where절과 on절에 포함되어 삭제된 데이터를 제외하는 것을 확인할 수 있습니다.

```java
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
```

```text
2022-02-13 23:29:53.099 DEBUG 4241 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        posts
        (content, deleted, title) 
    values
        (?, ?, ?)
2022-02-13 23:29:53.101 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-13 23:29:53.101 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-13 23:29:53.101 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-13 23:29:53.132 DEBUG 4241 --- [    Test worker] org.hibernate.SQL                        : 
    update
        posts 
    set
        content=?,
        deleted=?,
        title=? 
    where
        id=?
2022-02-13 23:29:53.132 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-13 23:29:53.132 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [true]
2022-02-13 23:29:53.132 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-13 23:29:53.133 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [4] as [BIGINT] - [1]
2022-02-13 23:29:53.140 DEBUG 4241 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        posts
        (content, deleted, title) 
    values
        (?, ?, ?)
2022-02-13 23:29:53.140 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-13 23:29:53.140 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-13 23:29:53.140 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-13 23:29:53.143 DEBUG 4241 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        posts
        (content, deleted, title) 
    values
        (?, ?, ?)
2022-02-13 23:29:53.144 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-13 23:29:53.144 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-13 23:29:53.144 TRACE 4241 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-13 23:29:53.148  WARN 4241 --- [    Test worker] o.h.engine.jdbc.spi.SqlExceptionHelper   : SQL Error: 0, SQLState: 23505
2022-02-13 23:29:53.148 ERROR 4241 --- [    Test worker] o.h.engine.jdbc.spi.SqlExceptionHelper   : ERROR: duplicate key value violates unique constraint "uk_posts_title_index"
  Detail: Key (title)=([FAAI] 공지사항) already exists.
```

post가 delete 쿼리를 통해 삭제되지 않아 실제로 동일한 title 컬럼의 데이터가 존재하지만 partial index에서 필터링되어 unique constraint를 위반하지 않습니다.
반면 post2가 삭제되지 않은 상태에서 post3를 insert하게 되면 constraint 위반 에러가 발생합니다.

### 발생할 수 있는 문제점

@Where 애노테이션이 적용된 엔티티의 연관관계가 @OneToOne 또는 @ManyToOne인 경우 조인을 사용한 조회 쿼리의 on절에 조건이 포함되지 않습니다.
자식 엔티티에 외래키를 설정하려면 부모 엔티티를 매핑해야하는데 그 과정에서 발생한 조회 쿼리에서 삭제 처리된 데이터를 필터링했다고 간주하기 때문입니다.
하지만 lazy loading으로 발생하는 조회 쿼리의 where절에는 조건이 포함됩니다. 만약 부모 엔티티로 참조하고 있는 데이터가 삭제 처리된 데이터일 경우
객체와 데이터베이스 상태의 일관성이 깨지는 문제가 발생할 수 있습니다.

```java
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
```

```text
2022-02-14 01:38:58.872 DEBUG 5595 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        posts
        (content, deleted, title) 
    values
        (?, ?, ?)
2022-02-14 01:38:58.874 TRACE 5595 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-14 01:38:58.874 TRACE 5595 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-14 01:38:58.874 TRACE 5595 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-14 01:38:58.885 DEBUG 5595 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        comments
        (content, deleted, post_id) 
    values
        (?, ?, ?)
2022-02-14 01:38:58.885 TRACE 5595 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [우와아~ 집에 갑시다.]
2022-02-14 01:38:58.885 TRACE 5595 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-14 01:38:58.885 TRACE 5595 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [BIGINT] - [13]
2022-02-14 01:38:58.899 DEBUG 5595 --- [    Test worker] org.hibernate.SQL                        : 
    update
        posts 
    set
        content=?,
        deleted=?,
        title=? 
    where
        id=?
2022-02-14 01:38:58.899 TRACE 5595 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-14 01:38:58.899 TRACE 5595 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [true]
2022-02-14 01:38:58.899 TRACE 5595 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-14 01:38:58.899 TRACE 5595 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [4] as [BIGINT] - [13]
2022-02-14 01:38:58.946 DEBUG 5595 --- [    Test worker] org.hibernate.SQL                        : 
    select
        comments0_.id as id1_0_0_,
        posts1_.id as id1_1_1_,
        comments0_.content as content2_0_0_,
        comments0_.deleted as deleted3_0_0_,
        comments0_.post_id as post_id4_0_0_,
        posts1_.content as content2_1_1_,
        posts1_.deleted as deleted3_1_1_,
        posts1_.title as title4_1_1_ 
    from
        comments comments0_ 
    inner join
        posts posts1_ 
            on comments0_.post_id=posts1_.id 
    where
        (
            comments0_.deleted = false
        )
```

부모 엔티티가 삭제되었음에도 불구하고 자식 엔티티를 패치 조인으로 할 때 발생하는 쿼리의 on절에 조건이 포함되지 않아
삭제 처리된 부모 엔티티가 함께 조회됩니다.

```java
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
```

```text
2022-02-14 01:39:25.030 DEBUG 5605 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        posts
        (content, deleted, title) 
    values
        (?, ?, ?)
2022-02-14 01:39:25.032 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-14 01:39:25.032 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-14 01:39:25.033 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-14 01:39:25.039 DEBUG 5605 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        comments
        (content, deleted, post_id) 
    values
        (?, ?, ?)
2022-02-14 01:39:25.040 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [우와아~ 집에 갑시다.]
2022-02-14 01:39:25.040 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-14 01:39:25.040 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [BIGINT] - [14]
2022-02-14 01:39:25.049 DEBUG 5605 --- [    Test worker] org.hibernate.SQL                        : 
    update
        posts 
    set
        content=?,
        deleted=?,
        title=? 
    where
        id=?
2022-02-14 01:39:25.050 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-14 01:39:25.050 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [true]
2022-02-14 01:39:25.050 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-14 01:39:25.050 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [4] as [BIGINT] - [14]
2022-02-14 01:39:25.056 DEBUG 5605 --- [    Test worker] org.hibernate.SQL                        : 
    select
        comments0_.id as id1_0_0_,
        comments0_.content as content2_0_0_,
        comments0_.deleted as deleted3_0_0_,
        comments0_.post_id as post_id4_0_0_ 
    from
        comments comments0_ 
    where
        comments0_.id=? 
        and (
            comments0_.deleted = false
        )
2022-02-14 01:39:25.057 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [BIGINT] - [11]
2022-02-14 01:39:25.068 DEBUG 5605 --- [    Test worker] org.hibernate.SQL                        : 
    select
        posts0_.id as id1_1_0_,
        posts0_.content as content2_1_0_,
        posts0_.deleted as deleted3_1_0_,
        posts0_.title as title4_1_0_ 
    from
        posts posts0_ 
    where
        posts0_.id=? 
        and (
            posts0_.deleted = false
        )
2022-02-14 01:39:25.069 TRACE 5605 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [BIGINT] - [14]
```

자식 엔티티를 조회한 후에 삭제 처리된 부모 엔티티를 lazy loading을 통해 조회할 때 발생하는 쿼리의 where절에는 조건이 포함됩니다.
하지만 외래키가 존재함에도 조회된 결과가 없는 데이터 일관성 불일치로 인해 EntityNotFoundException 예외가 발생합니다.
연관관계 엔티티에 @NotFound(action = NotFoundAction.IGNORE)을 적용하여 예외발생을 막을 수 있지만 조회 결과가 없는 경우 프록시 객체가 아닌 null을 주입하기 위해
패치 타입이 Lazy로 설정되더라도 Eager로 적용되어 즉시 조회 쿼리가 발생하고 삭제된 엔티티에 null이 주입되어 데이터베이스 레벨에서는 외래키가 존재하지만
엔티티는 존재하지 않게 되어 nullable = false임에도 값이 존재하지 않는 것처럼 보이게 됩니다. 또한 삭제 처리된 부모 엔티티를 패치 조인할 때 발생하는
데이터 일관성 불일치 문제를 해결할 수 없기 때문에 근본적인 해결 방법이 될 수 없습니다.

그렇다면 어떤 경우에 삭제 처리된 부모 엔티티를 참조하게 되는 것일까요?

1. 삭제 처리된 엔티티를 조회하지 않고 프록시로 매핑하는 경우

```java
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
```

해당 식별키를 가지는 부모 엔티티를 조회하지 않고 프록시 객체로 연관관계를 매핑하는 경우 이미 삭제 처리된
데이터일지라도 foreign key constraint를 위반하지 않기 때문에 삭제 처리된 데이터를 외래키로 참조하게 됩니다.

2. 조회 시점에는 삭제 처리가 되지 않았지만 다른 트랜잭션에 의해 삭제 처리된 경우

```java
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

    Comments comment = entityManager.find(Comments.class, commentTx2.getId());

    // then
    assertThrows(
        EntityNotFoundException.class,
        () -> comment.getPost().getContent() // lazy loading & exception occurs
    );
}
```

```postgresql
BEGIN; -- tx1
    select
        posts0_.id as id1_1_0_,
        posts0_.content as content2_1_0_,
        posts0_.deleted as deleted3_1_0_,
        posts0_.title as title4_1_0_
    from
        posts posts0_
    where
        posts0_.id= 1
      and (
        posts0_.deleted = false
        )

    select
        comments0_.post_id as post_id4_0_1_,
        comments0_.id as id1_0_1_,
        comments0_.id as id1_0_0_,
        comments0_.content as content2_0_0_,
        comments0_.deleted as deleted3_0_0_,
        comments0_.post_id as post_id4_0_0_
    from
        comments comments0_
    where
        (
            comments0_.deleted = false
            )
      and comments0_.post_id= 1
    
                                    BEGIN; -- tx2
                                        select
                                            posts0_.id as id1_1_0_,
                                            posts0_.content as content2_1_0_,
                                            posts0_.deleted as deleted3_1_0_,
                                            posts0_.title as title4_1_0_
                                        from
                                            posts posts0_
                                        where
                                            posts0_.id= 1
                                          and (
                                            posts0_.deleted = false
                                            );
                                        
                                        insert
                                        into
                                            comments
                                            (content, deleted, post_id)
                                        values
                                            ('우와아~ 집에 갑시다.', false, 1);
                                    COMMIT;
    
    update
        posts
    set
        content= '오늘은 다들 일하지 말고 집에 가세요!',
        deleted= true,
        title= '[FAAI] 공지사항'
    where
        id= 1;
COMMIT;
```

부모 엔티티를 삭제하는 트랜잭션이 커밋되기 전에 엔티티를 조회 후 매핑하는 경우 자식 엔티티를 insert한 후에 삭제 처리됩니다.
마찬가지로 foreign key constraint를 위반하지 않기 때문에 삭제 처리된 데이터를 외래키로 참조하게됩니다.


### 해결방안

```java
Posts post = entityManager.getReference(Posts.class, id));
Comments comment = new Comments("우와아~ 집에 갑시다.", post); // X
```
Hard Delete를 사용하는 경우 foreign key 제약조건에 의해 EntityNotFoundException 예외가 발생하지 않습니다. Soft Delete에서는
delete 쿼리로 인한 물리적인 데이터 삭제가 이루어지지 않기 때문에 삭제 처리되더라도 foreign key constraint를 위반하지 않기 때문에
외래키로 설정될 수 있게 되면서 해당 문제가 발생할 수 있는 것입니다. 그러므로 반드시 프록시 객체를 주입하여 외래키를 설정해서는 안됩니다.
또한 현재 트랜잭션에서 삭제 처리중인 데이터를 다른 트랜잭션에서 조회하여 외래키로 설정할 수 없도록 방지해야합니다.

#### Optimistic Locking

```java
@Version
private long version;
```

낙관적 락을 사용하기 위해 엔티티 클래스에 version 필드를 추가합니다. 엔티티를 대상으로 update 쿼리가 발생하는 경우
version을 증가시키고 조회 시점의 version과 다른 경우 OptimisticLockException 예외를 발생합니다.

```java
@SQLDelete(sql = "UPDATE posts SET deleted = true, version = version + 1 WHERE id = ? AND version = ?")
```

@SQLDelete 애노테이션을 사용하는 경우 쿼리의 조건절에 version 컬럼을 추가하고 version을 증가시킵니다.

```java
@Test
void 트랜잭션_경합조건에_따라_삭제처리된_데이터를_매핑할수_없도록_낙관적락으로_방지한다() {
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

    Posts postTx2 = em2.find(Posts.class, post.getId(), LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    Comments commentTx2 = new Comments("우와아~ 집에 갑시다.", postTx2);

    em2.persist(commentTx2);
    tx2.commit();
    // tx2 end

    // then
    RollbackException rollbackException = assertThrows(
        RollbackException.class,
        tx1::commit // tx1 end
    );
    assertTrue(rollbackException.getCause() instanceof OptimisticLockException);
}
```

```postgresql
BEGIN; -- tx1
    select
        posts0_.id as id1_1_0_,
        posts0_.content as content2_1_0_,
        posts0_.deleted as deleted3_1_0_,
        posts0_.title as title4_1_0_
    from
        posts posts0_
    where
        posts0_.id= 1
      and (
        posts0_.deleted = false
        )

    select
        comments0_.post_id as post_id4_0_1_,
        comments0_.id as id1_0_1_,
        comments0_.id as id1_0_0_,
        comments0_.content as content2_0_0_,
        comments0_.deleted as deleted3_0_0_,
        comments0_.post_id as post_id4_0_0_
    from
        comments comments0_
    where
        (
            comments0_.deleted = false
            )
      and comments0_.post_id= 1
    
                                    BEGIN; -- tx2
                                        select
                                            posts0_.id as id1_1_0_,
                                            posts0_.content as content2_1_0_,
                                            posts0_.deleted as deleted3_1_0_,
                                            posts0_.title as title4_1_0_
                                        from
                                            posts posts0_
                                        where
                                            posts0_.id= 1
                                          and (
                                            posts0_.deleted = false
                                            );
                                        
                                        insert
                                        into
                                            comments
                                            (content, deleted, post_id)
                                        values
                                            ('우와아~ 집에 갑시다.', false, 1);
                                            
                                        update
                                            posts 
                                        set
                                            version= 1
                                        where
                                            id= 1
                                            and version= 0
                                    COMMIT;
    
    update
        posts
    set
        content= '오늘은 다들 일하지 말고 집에 가세요!',
        deleted= true,
        title= '[FAAI] 공지사항',
        version= 1
    where
        id= 1
        and version= 0
COMMIT;
```

TODO 버전 충돌 내용

#### Pessimistic Locking

```java
@Test
void 트랜잭션_경합조건에_따라_삭제처리된_데이터를_매핑할수_없도록_비관적락으로_방지한다() throws ExecutionException, InterruptedException {
    // given
    CountDownLatch countDownLatch = new CountDownLatch(1);
    EntityManager em = entityManagerFactory.createEntityManager();
    Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");

    em.getTransaction().begin();
    em.persist(post);
    em.getTransaction().commit();

    // when
    CompletableFuture<Void> tx1Result = CompletableFuture.runAsync(() -> {
        // tx1 start
        EntityManager em1 = entityManagerFactory.createEntityManager();
        EntityTransaction tx1 = em1.getTransaction();
        tx1.begin();
    
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Posts postTx1 = em1.find(
            Posts.class,
            post.getId(),
            LockModeType.PESSIMISTIC_READ,
            Collections.singletonMap(AvailableSettings.JPA_LOCK_TIMEOUT, 2000L)
        );
    
        if (CollectionUtils.isEmpty(postTx1.getComments())) {
            postTx1.delete();
        }
    
        tx1.commit();
        // tx1 end
    });

    CompletableFuture<Long> tx2Result = CompletableFuture.supplyAsync(() -> {
        // tx2 start
        EntityManager em2 = entityManagerFactory.createEntityManager();
        EntityTransaction tx2 = em2.getTransaction();

        tx2.begin();

        Posts postTx2 = em2.find(
            Posts.class,
            post.getId(),
            LockModeType.PESSIMISTIC_WRITE,
            Collections.singletonMap(AvailableSettings.JPA_LOCK_TIMEOUT, 2000L)
        );
        countDownLatch.countDown();
        Comments commentTx2 = new Comments("우와아~ 집에 갑시다.", postTx2);
        em2.persist(commentTx2);

        tx2.commit();
        // tx2 end

        return commentTx2.getId();
    });

    CompletableFuture
        .allOf(tx1Result, tx2Result)
        .join();

    Comments comment = entityManager.find(Comments.class, tx2Result.get());

    // then
    assertFalse(comment.getPost().isDeleted());
}
```

```postgresql
BEGIN; -- tx1
    
                                    BEGIN; -- tx2
                                        select
                                            posts0_.id as id1_1_0_,
                                            posts0_.content as content2_1_0_,
                                            posts0_.deleted as deleted3_1_0_,
                                            posts0_.title as title4_1_0_
                                        from
                                            posts posts0_
                                        where
                                            posts0_.id= 1
                                          and (
                                            posts0_.deleted = false
                                            ) for update
                                        
                                        insert
                                        into
                                            comments
                                            (content, deleted, post_id)
                                        values
                                            ('우와아~ 집에 갑시다.', false, 1);
                                            
                                        update
                                            posts 
                                        set
                                            version= 1
                                        where
                                            id= 1
                                            and version= 0
                                    COMMIT;

    select
        posts0_.id as id1_1_0_,
        posts0_.content as content2_1_0_,
        posts0_.deleted as deleted3_1_0_,
        posts0_.title as title4_1_0_
    from
        posts posts0_
    where
        posts0_.id= 1
      and (
        posts0_.deleted = false
        ) for share

    select
        comments0_.post_id as post_id4_0_1_,
        comments0_.id as id1_0_1_,
        comments0_.id as id1_0_0_,
        comments0_.content as content2_0_0_,
        comments0_.deleted as deleted3_0_0_,
        comments0_.post_id as post_id4_0_0_
    from
        comments comments0_
    where
        (
            comments0_.deleted = false
            )
      and comments0_.post_id= 1
COMMIT;
```

TODO 락 내용

#### 그 외 다른 방법들
Etc synchronous, distribute locking(Redis)

## 마무리

TODO 반드시 Soft Delete를 사용하는 것보다는 상황과 필요에 따라 Soft Delete를 사용하는 것이 좋다는 내용.
TODO 그리고 단순히 삭제 구분 값 하나를 추가한 것만으로는 Soft Delete를 구현했다고는 볼 수 없으며 고려해야할 점들이 많다는 내용.

references
- https://www.postgresql.org/docs/14/sql-vacuum.html
- https://www.postgresql.org/docs/14/sql-reindex.html
- https://www.postgresql.org/docs/14/indexes-partial.html
- https://docs.jboss.org/hibernate/orm/5.4/javadocs/org/hibernate/annotations/Where.html
- https://docs.jboss.org/hibernate/orm/5.4/javadocs/org/hibernate/annotations/SQLDelete.html
- https://www.objectdb.com/java/jpa/persistence/lock

소스 코드
- https://github.com/sinbom/implement-soft-delete-hibernate


