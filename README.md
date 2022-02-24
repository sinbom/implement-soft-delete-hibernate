# JPA + Hibernate 기반의 개발 환경에서 Soft Delete 구현하기

## Soft Delete란?

`Soft Delete` 또는 `Logical Delete`는 delete 쿼리를 사용하여 물리적으로 데이터를 삭제하는 것이 아니라
update 쿼리를 통해 상태를 변경하여 삭제된 데이터로 구분할 수 있도록 논리적으로 데이터를 삭제하는 것을 의미합니다.
`Soft Delete`는 물리적인 데이터 삭제로 발생할 수 있는 문제를 방지하고 쉽게 복원할 필요가 있거나 
삭제된 데이터들을 보관하여 데이터로써 활용할 필요나 가치가 있는 경우에 사용됩니다.

삭제된 데이터를 구분하기 위한 컬럼으로 다음의 데이터 타입을 사용할 수 있습니다.
- `deleted(boolean, int)` 컬럼의 값이 true 또는 1인 경우
- `deleted_at(timestamp)` 컬럼의 값이 현재 timestamp보다 이전인 경우

## Soft Delete & Hard Delete 비교

`Soft Delete`와 `Hard Delete`를 여러가지 관점에서 비교해보겠습니다.

|                   | Soft Delete                                                   | Hard Delete                       |
|-------------------|---------------------------------------------------------------|-----------------------------------|
| 삭제                | UPDATE table SET deleted = true WHERE id = ?              | DELETE table FROM WHERE id = ? |
| 조회                | SELECT * FROM table WHERE deleted = false                 | SELECT * FROM table |
| 복원                | UPDATE table SET deleted = false WHERE id = ?             | 백업, 리플리케이션 또는 쿼리 로그를 통해 복원합니다. |
| 디스크 사용량           | 삭제시 테이블의 디스크 사용량이 감소하지 않습니다.                                  | 삭제시 테이블의 디스크 사용량이 감소합니다. |
| unique constraint | CREATE UNIQUE INDEX index ON table(column1, column2, ... column_n) WHERE deleted = false | ALTER TABLE table ADD CONSTRAINT constraint UNIQUE (column1, column2, ... column_n) |
| on delete cascade | delete 쿼리가 아닌 update 쿼리로 삭제하기 때문에 사용할 수 없습니다. | ALTER TABLE child ADD CONSTRAINT constraint FOREIGN KEY (parent_id) REFERENCES parent(id) ON DELETE CASCADE |

### 삭제(`soft delete` > `hard delete`)
`Soft Delete`의 삭제는 update 쿼리로 처리되고 `Hard Delete`의 삭제는 delete 쿼리로 처리됩니다. update와 delete 쿼리 모두 exclusive lock을 획득하지만 
delete 쿼리는 테이블의 레코드와 인덱스의 노드를 삭제 처리해야하는 반면 update 쿼리는 삭제 구분 컬럼만을 변경하기 때문에 조금 더 빠르게 처리할 수 있습니다.

### 조회(`soft delete` < `hard delete`)
`Soft Delete`를 사용하면 삭제된 데이터를 제외해야 하기 때문에 모든 조회 쿼리의 where, on절에 삭제된 데이터를 제외하는 조건이 포함되어야 합니다.
조회 쿼리에 조건을 누락하게 되면 애플리케이션이 잘못된 결과를 반환할 수 있지만 이는 개발자가 너무나도 실수하기 쉬운 부분이기 때문에 항상 주의가 필요하게 됩니다.

### 복원(`soft delete` > `hard delete`)
`Soft Delete`를 사용하면 삭제 구분 값을 변경하여 간단하게 복원할 수 있지만 `Hard Delete`는 백업과 장애 발생 시점의 간격과 리플리케이션 또는 쿼리 로그 유무에 따라 복원이 어려울 수 있습니다.

### 디스크 사용량(`soft delete` < `hard delete`)
`Soft Delete` 를 사용할 때 큰 단점은 바로 테이블과 인덱스에서 삭제된 데이터가 물리적으로 제거되지 않는다는 점입니다.
사용중인 DBMS의 `partial index` 지원 유무에 따라 인덱스의 사용량은 다를 수 있지만 데이터가 물리적으로 삭제되지 않기 때문에 
지속적으로 디스크 사용량이 증가하고 데이터를 조회하는 과정에서 삭제된 데이터들이 함께 조회되기 때문에 데이터가 쌓일수록 조회 성능이 저하될 수 있습니다.

`Hard Delete`와 `Soft Delete`를 사용할 때 테이블과 인덱스의 디스크 사용량을 확인해보겠습니다.  

```postgresql
CREATE TABLE foo
(
	deleted BOOLEAN NOT NULL,
	name VARCHAR(255) NOT  NULL
);

CREATE INDEX FOO_NAME_INDEX ON foo(name);
CREATE INDEX FOO_NAME_PARTIAL_INDEX ON foo(name) WHERE deleted = false;

do $$
    begin
        for i in 1..100000 loop
                INSERT INTO foo (name, deleted) VALUES (CONCAT('bar ', i), false);
            end loop;
    end;
$$;

VACUUM FULL foo;

SELECT pg_size_pretty(pg_total_relation_size('foo'))           AS "총 용량",
       pg_size_pretty(pg_relation_size('foo'))                 AS "테이블 용량",
       pg_size_pretty(pg_indexes_size('foo'))                  AS "인덱스 용량",
       pg_size_pretty(pg_table_size('foo_name_index'))         AS "foo_name_index",
       pg_size_pretty(pg_table_size('foo_name_partial_index')) AS "foo_name_partial_index";
```

```text
 총 용량   | 테이블 용량    | 인덱스 용량   | foo_name_index  | foo_name_partial_index
---------+-------------+-------------+----------------+------------------------
 15 MB   | 4328 kB     | 11 MB       | 5376 kB        | 5376 kB
```

`Soft Delete` 테스트 테이블과 인덱스를 생성합니다. 100000개의 테스트 데이터를 insert하고
테이블과 인덱스의 디스크 사용량을 확인하기 위해 VACUUM, REINDEX 명령어를 실행하여 변경 또는 삭제된 
데이터들이 차지 하고있는 디스크 공간을 확보 후 용량을 확인합니다.

- VACCUM : 변경 또는 삭제 처리된 데이터들이 차지하고 있는 테이블의 디스크 공간을 확보합니다.
- REBUILD INDEX : 변경 또는 삭제 처리된 인덱스 트리의 노드들을 제외한 인덱스를 재색인하여 디스크 공간을 확보합니다.

```postgresql
DELETE FROM foo;
REINDEX INDEX foo_name_index;
REINDEX INDEX foo_name_partial_index;
VACUUM FULL foo;
```

```text
 총 용량   | 테이블 용량   | 인덱스 용량    | foo_name_index | foo_name_partial_index
---------+-------------+-------------+----------------+------------------------
 16 kB   | 0 bytes     | 16 kB       | 8192 bytes     | 8192 bytes
```

`Hard Delete` 는 물리적으로 데이터를 삭제하여 테이블과 인덱스의 디스크 사용량이 감소하였습니다.

```postgresql
UPDATE foo SET deleted = true;
REINDEX INDEX foo_name_partial_index;
REINDEX INDEX foo_name_index;
VACUUM FULL foo;
```

```text
 총 용량   | 테이블 용량   | 인덱스 용량    | foo_name_index | foo_name_partial_index
---------+-------------+-------------+----------------+------------------------
 7424 kB | 4328 kB     | 3096 kB     | 3088 kB        | 8192 bytes
```

`Sofe Delete` 는 논리적으로 데이터를 삭제하여 테이블과 인덱스의 디스크 사용량에 변화가 없기 때문에 지속적으로 사용량이 증가합니다.
`partial index`를 사용하면 논리적으로 삭제된 데이터가 인덱스에서 필터링되어 인덱스에서 삭제되고 디스크 사용량이 감소합니다. 삭제된 노드가 인덱스 트리에서 제거되어
삭제된 노드로 인해 증가한 물리적인 노드의 접근 횟수가 논리적인 접근 횟수와 동일해지므로 일반 index를 사용하는 것보다 조회 쿼리의 성능에 더 좋은 영향을 줄 수 있습니다.

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

각 쿼리의 실행계획을 확인해본 결과, (1)번의 조회 쿼리를 제외한 모든 쿼리의 실행계획은 index scan이 아닌 sequential scan이 선택되었습니다.
`partial index`는 반드시 완전히 동일한 필터링 조건이 포함된 쿼리에만 적용된다는 것을 알 수 있습니다.

>ℹ️ NOTE
>
> 테이블의 용량이 매우 작거나 거의 모든 테이블을 조회하는 경우 index를 통해 data block에 접근하는 것보다 sequential scan을 사용하는 것이 더 빠르기 때문에 optimizer가 index scan을 사용하지 않을 수도 있습니다.


### Unique Constraint(`Soft Delete` <= `Hard Delete`)
`Soft Delete`를 사용하면 남아있는 삭제된 데이터로 인해 컬럼의 값이 중복되어 unique constraint를 위반하게 될 수 있습니다.

`partial index` 지원 유무에 따른 unique constraint 적용 방법을 살펴보겠습니다.

```postgresql
CREATE TABLE bar
(
    deleted_at TIMESTAMP NOT NULL DEFAULT 'epoch',
    name       varchar(255)        NOT NULL
);

ALTER TABLE bar ADD CONSTRAINT BAR_NAME_UNIQUE_CONSTRAINT UNIQUE (deleted_at, name);

INSERT INTO bar(deleted_at, name)VALUES (now(), '삭제된 데이터는 중복 가능');
INSERT INTO bar(deleted_at, name)VALUES (now(), '삭제된 데이터는 중복 가능');
INSERT INTO bar(name)VALUES ('삭제된 데이터가 아니면 중복 불가능');
INSERT INTO bar(name)VALUES ('삭제된 데이터가 아니면 중복 불가능');
```

```text
ERROR:  duplicate key value violates unique constraint "bar_name_unique_constraint"
DETAIL:  Key (deleted_at, name)=(1970-01-01 00:00:00, 삭제된 데이터가 아니면 중복 불가능) already exists.
```

`partial index`를 지원하지 않는 경우 삭제된 데이터로 인해 unique constraint를 위반할 수 있습니다. 삭제 구분 컬럼의 데이터 타입을 timestamp로 사용하여
unique constraint 구성 컬럼에 포함시켜 값이 중복되지 않도록 합니다. null 값은 unique constraint을 위반하지 않기 때문에 삭제되지 않은 데이터는 epoch time(1970년 1월 1일 00:00:00)을 사용합니다.
unique index 구성 컬럼에 삭제 구분 컬럼이 포함되어 인덱스의 사이즈가 불필요하게 증가합니다.

```postgresql
CREATE TABLE foo
(
    deleted BOOLEAN      NOT NULL,
    name    VARCHAR(255) NOT NULL
);

CREATE UNIQUE INDEX FOO_NAME_PARTIAL_UNIQUE_INDEX ON foo (name) WHERE deleted = false;

INSERT INTO foo(deleted, name) VALUES (true, '삭제된 데이터는 중복 가능');
INSERT INTO foo(deleted, name) VALUES (true, '삭제된 데이터는 중복 가능');
INSERT INTO foo(deleted, name) VALUES (false, '삭제된 데이터가 아니면 중복 불가능');
INSERT INTO foo(deleted, name) VALUES (false, '삭제된 데이터가 아니면 중복 불가능');
```

```text
ERROR:  duplicate key value violates unique constraint "foo_name_partial_unique_index"
DETAIL:  Key (name)=(삭제된 데이터가 아니면 중복 불가능) already exists.
```

unique constraint에는 필터링 조건을 설정할 수 없지만 `partial index`를 사용하여 unique index를 생성하면 unique constraint를 구현할 수 있습니다. 
삭제된 데이터는 `partial index`에서 필터링되어 unique constraint를 위반하지 않지만 삭제되지 않은 데이터는 위반 에러가 발생합니다.

>ℹ️ NOTE
>
> unique constraint를 생성하면 내부적으로 unique index를 생성하여 처리하기 때문에 unique constraint와 unique index는 동일하게 작동합니다.

### On Delete Cascade(`Soft Delete` < `Hard Delete`)

`Soft Delete`는 delete 쿼리를 사용하지 않기 때문에 on delete cascade를 사용할 수 없습니다. `Soft Delete`에서 cascade 삭제를 사용하기 위해서는 
애플리케이션 또는 데이터베이스 레벨에서 트리거로 직접 구현해야합니다.

```postgresql
CREATE TABLE foo
(
    id      BIGINT PRIMARY KEY,
    deleted BOOLEAN      NOT NULL,
    name    VARCHAR(255) NOT NULL
);

CREATE TABLE bar
(
    id      BIGINT PRIMARY KEY,
    deleted BOOLEAN                    NOT NULL,
    name    VARCHAR(255)               NOT NULL,
    foo_id  BIGINT REFERENCES foo (id) NOT NULL
);

INSERT INTO foo(id, deleted, name) VALUES (1, false, '부모');
INSERT INTO bar(id, deleted, name, foo_id) VALUES (1, false, '자식', 1);

CREATE FUNCTION soft_delete_cascade()
    RETURNS TRIGGER AS
$$
BEGIN
    UPDATE bar SET deleted = true WHERE id = old.id;
    return NULL;
END;
$$
    LANGUAGE plpgsql;

CREATE TRIGGER soft_delete_trigger
    AFTER UPDATE
    ON foo
    FOR EACH ROW
EXECUTE PROCEDURE soft_delete_cascade();

UPDATE foo SET deleted = true WHERE id = 1;

SELECT f.deleted, b.deleted
FROM foo f INNER JOIN bar b ON f.id = b.foo_id
WHERE f.id = 1;
```

```text
 deleted | deleted
---------+---------
 t       | t
```

데이터의 삭제 구분 값을 변경했을 때 해당 데이터를 참조 중인 테이블 로우의 삭제 구분 값을 함께 변경할 수 있도록 함수를 작성하고 트리거에 설정합니다.
애플리케이션에서 구현하는 방법은 ORM 프레임워크 사용 유무에 따라 다르기 때문에 Hibernate 환경에서 구현해보면서 함께 살펴보도록 하겠습니다.


## JPA + Hibernate 개발 환경에서의 구현

Hibernate를 사용하는 애플리케이션에서 `Soft Delete`를 구현하는 방법과 발생할 수 있는 문제들을 해결할 수 있는 방법들에 대해서 알아보겠습니다. 

개발 환경
- Spring Boot 2.5.10
- Postgresql 14.1
- Java 11(Open Jdk Azul)

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
    
}
```

예제 소스 코드에서 사용할 게시글과 댓글 엔티티 클래스입니다. soft delete를 구현하기 위해 boolean 타입의 필드로 삭제 유무를 구분하고 메소드를 통해 객체의 상태를 변경하여 삭제합니다.

```java
@Where(clause = "deleted = false")
```

엔티티 조회 쿼리의 where절에 반드시 포함되는 조건을 설정할 수 있습니다. 개발자의 실수로 쉽게 누락할 수 있기 때문에 애노테이션을 사용해서 글로벌하게 설정하는 것이 좋습니다.
또한 Lazy Loading으로 발생하는 조회 쿼리의 where절에 조건을 포함시키기 위해서는 반드시 사용해야합니다. 하지만 JPQL 또는 HQL이 아닌 native SQL을 사용할 때는 적용되지 않기 때문에 주의해야합니다.

```java
@SQLDelete(sql = "UPDATE comments SET deleted = true WHERE id = ?")
```

엔티티의 상태를 removed로 변경할 때 발생하는 쿼리를 설정할 수 있습니다. 연관관계 매핑 애노테이션들(@OneToMany, @ManyToOne, @OneToOne)의 cascade = CascadeType.REMOVE 옵션과 함께 사용하면
별도의 구현 없이 on soft delete cascade를 적용할 수 있습니다.

```postgresql
CREATE UNIQUE INDEX IF NOT EXISTS UK_POSTS_TITLE_INDEX ON posts(title) WHERE deleted = false;
```

삭제된 데이터를 인덱스에서 필터링하여 unique constraint를 적용합니다. `partial index`는 JPA 스펙에 포함되어 있지 않기 때문에 애노테이션 기반으로 생성할 수 없습니다.

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

인덱스 생성 쿼리를 `schema.sql` 파일에 작성하고 classpath에 위치합니다. `spring.sql.init.mode`를 `always`로 설정하여 애플리케이션 실행시 초기화 script로 사용하도록 설정합니다. 
초기화 script에서 발생한 쿼리 로그를 확인하려면 `logging.level.springframework.jdbc.datasource.init.ScriptUtils`를 `DEBUG`로 설정합니다.

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

삭제 구분 필드의 값을 true로 변경하여 삭제합니다. 조회 쿼리의 where절에 delete = false 조건이 포함되어 삭제된 데이터를 제외하고 조회하는 것을 확인할 수 있습니다.

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

엔티티의 상태를 removed로 변경하여 자식 엔티티를 cascade 삭제합니다. 조인을 사용한 조회 쿼리의 where절과 on절에 delete = false 조건이 포함되어 삭제된 데이터를 제외하고 조회하는 것을 확인할 수 있습니다.

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

삭제된 데이터는 `partial index`에서 제외되어 unique constraint를 위반하지 않지만 삭제되지 않은 상태에서 동일한 title 컬럼의 값을 입력하면 constraint 위반 에러가 발생합니다.

### 발생할 수 있는 문제점

@Where 애노테이션이 적용된 엔티티와의 연관관계가 @OneToOne 또는 @ManyToOne인 경우 조인을 사용한 조회 쿼리의 on절에 조건이 포함되지 않지만
Lazy Loading으로 발생하는 조회 쿼리의 where절에는 조건이 포함됩니다. 만약 부모 엔티티로 참조하고 있는 데이터가 삭제 처리된 데이터일 경우
객체와 데이터베이스 상태의 일관성이 불일치하는 문제가 발생합니다.

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

삭제된 부모 엔티티를 Lazy Loading할 때 데이터베이스에는 참조 중인 외래키가 존재하지만 조회된 엔티티가 없어 일관성 불일치로 인해 EntityNotFoundException 예외가 발생합니다.
부모 엔티티 연관관계 매핑에 @NotFound(action = NotFoundAction.IGNORE)을 적용하여 EntityNotFoundException 예외 발생을 막을 수 있지만 조회 결과가 없는 경우 
프록시 객체가 아닌 null을 주입하기 위해 패치 타입이 Lazy로 설정되어 있어도 무시되어 Eager로 간주되기 때문에 N + 1 발생으로 인한 성능 문제가 발생할 수 있고 객체와 데이터베이스 상태의 일관성 불일치를 해결할 수 없습니다.

```java
@Test
void 삭제처리된_부모엔티티를_조인으로_조회하면_데이터_일관성_불일치가_발생한다 {
    // given
    Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");
    Comments comment = new Comments("우와아~ 집에 갑시다.", post);

    // when
    entityManager.persist(post);
    entityManager.persist(comment);
    post.delete();
    entityManager.flush();
    entityManager.clear();
    List<Comments> fetchJoinResult = entityManager
            .createQuery("SELECT c FROM Comments c INNER JOIN FETCH c.post p", Comments.class)
            .getResultList();
    entityManager.clear();
    List<Object[]> emptyResult = entityManager
            .createQuery("SELECT c, p FROM Comments c INNER JOIN c.post p ON p.deleted = false", Object[].class)
            .getResultList();
    List<Object[]> leftJoinResult = entityManager
            .createQuery("SELECT c, p FROM Comments c LEFT JOIN c.post p ON p.deleted = false", Object[].class)
            .getResultList();

    // then
    assertEquals(fetchJoinResult.size(), 1);
    assertTrue(fetchJoinResult.get(0).getPost().isDeleted());
    assertTrue(emptyResult.isEmpty());
    assertEquals(leftJoinResult.size(), 1);
    assertThrows(
        EntityNotFoundException.class,
        () -> ((Comments) leftJoinResult.get(0)[0]).getPost().getContent() // lazy loading & exception occurs
    );
    assertNull(leftJoinResult.get(0)[1]);
}
```

```text
2022-02-20 16:01:23.320 DEBUG 17475 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        posts
        (content, deleted, title) 
    values
        (?, ?, ?)
2022-02-20 16:01:23.321 TRACE 17475 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-20 16:01:23.322 TRACE 17475 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-20 16:01:23.322 TRACE 17475 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-20 16:01:23.327 DEBUG 17475 --- [    Test worker] org.hibernate.SQL                        : 
    insert 
    into
        comments
        (content, deleted, post_id) 
    values
        (?, ?, ?)
2022-02-20 16:01:23.328 TRACE 17475 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [우와아~ 집에 갑시다.]
2022-02-20 16:01:23.328 TRACE 17475 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [false]
2022-02-20 16:01:23.328 TRACE 17475 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [BIGINT] - [28]
2022-02-20 16:01:23.335 DEBUG 17475 --- [    Test worker] org.hibernate.SQL                        : 
    update
        posts 
    set
        content=?,
        deleted=?,
        title=? 
    where
        id=?
2022-02-20 16:01:23.335 TRACE 17475 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [1] as [VARCHAR] - [오늘은 다들 일하지 말고 집에 가세요!]
2022-02-20 16:01:23.335 TRACE 17475 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [2] as [BOOLEAN] - [true]
2022-02-20 16:01:23.335 TRACE 17475 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [3] as [VARCHAR] - [[FAAI] 공지사항]
2022-02-20 16:01:23.335 TRACE 17475 --- [    Test worker] o.h.type.descriptor.sql.BasicBinder      : binding parameter [4] as [BIGINT] - [28]
2022-02-20 16:01:23.347 DEBUG 17475 --- [    Test worker] org.hibernate.SQL                        : 
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
2022-02-20 16:01:23.357 DEBUG 17475 --- [    Test worker] org.hibernate.SQL                        : 
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
            and (
                posts1_.deleted=false
            ) 
    where
        (
            comments0_.deleted = false
        )
2022-02-20 16:01:23.362 DEBUG 17475 --- [    Test worker] org.hibernate.SQL                        : 
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
    left outer join
        posts posts1_ 
            on comments0_.post_id=posts1_.id 
            and (
                posts1_.deleted=false
            ) 
    where
        (
            comments0_.deleted = false
        )
2022-02-20 16:01:23.369 DEBUG 17475 --- [    Test worker] org.hibernate.SQL                        : 
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
```

Hibernate는 패치 조인 쿼리에 데이터베이스와 엔티티의 일관성 일치를 위해 on절에 직접 조건을 사용하는 경우 QuerySyntaxException 에러가 발생합니다. 
패치 조인으로 조회하는 경우 삭제된 부모 엔티티가 함께 조회되기 때문에 패치 조인을 사용하지 않고 내부 조인 쿼리의 on절에 조건을 추가하여 조회하면 삭제되지 않은 자식 엔티티까지 조회되지 않게 됩니다. 
삭제되지 않은 자식 엔티티를 조회하기 위해 외부 조인을 사용하더라도 결국 Lazy Loading으로 인해 동일한 문제가 발생하게 됩니다.

### 문제의 발생 이유

그렇다면 어떤 경우에 삭제된 부모 엔티티를 매핑하게 되는 것일까요?

#### 1. 삭제 처리된 엔티티를 조회하지 않고 프록시로 매핑하는 경우

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

해당 식별키를 가진 부모 엔티티가 이미 삭제처리 되었더라도 조회하지 않고 프록시 객체로 연관관계를 설정하는 경우 삭제된 부모 엔티티를 매핑하게 될 수 있습니다.

#### 2. 자식 엔티티가 매핑중인 부모 엔티티를 삭제하는 경우

```java
@Test
void 자식엔티티가_매핑중인_부모엔티티를_삭제하면_데이터_일관성_불일치가_발생한다() {
    // given
    Posts post = new Posts("[FAAI] 공지사항", "오늘은 다들 일하지 말고 집에 가세요!");
    Comments comment = new Comments("우와아~ 집에 갑시다.", post);
    entityManager.persist(post);
    entityManager.persist(comment);

    // when
    post.delete();
    entityManager.flush();
    entityManager.clear();
    Comments find = entityManager.find(Comments.class, comment.getId());

    assertThrows(
        EntityNotFoundException.class,
        () -> find.getPost().getContent() // lazy loading & exception occurs
    );
}
```

자식 엔티티가 매핑중인 부모 엔티티를 삭제가 불가능하게 처리하거나 자식 엔티티를 함께 삭제하는 방식으로 처리하지 않는 경우 삭제된 부모 엔티티를 매핑하게 될 수 있습니다.
 
#### 3. 조회 시점에는 삭제 처리가 되지 않았지만 다른 트랜잭션에 의해 삭제 처리된 경우

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

    if (postTx1.getComments().isEmpty()) {
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

부모 엔티티를 삭제하는 트랜잭션이 커밋되기 전에 다른 트랜잭션에서 부모 엔티티를 조회 후 연관관계를 매핑하는 경우 삭제된 부모 엔티티를 매핑하게 될 수 있습니다.


### 해결방안

`Hard Delete`를 사용하는 경우 foreign key constraint에 의해 참조중인 데이터가 존재하면 삭제되지 않거나 cascade 삭제되고 존재하지 않는 참조하는 경우 에러가 발생하지만
`Soft Delete`는 삭제와 삭제된 데이터를 참조하더라도 foreign key constraint 위반이 발생하지 않기 때문에 삭제 전략에 따라 별도의 로직과 동시성 처리가 필요합니다.

#### 프록시 객체를 통한 매핑을 사용하지 않고 삭제 전략에 따라 로직을 구현한다.
```java
// 잘못된 경우
Posts post = entityManager.getReference(Posts.class, id));
Comments comment = new Comments("우와아~ 집에 갑시다.", post);

post.delete();

// 올바른 경우
Posts post = entityManager.find(Posts.class, id));
Comments comment = new Comments("우와아~ 집에 갑시다.", post);

if (posts.getComments().isEmpty()) {
    post.delete();
}
```
객체와 데이터베이스의 일관성 불일치 문제를 해결하기 위해 연관관계 매핑에 사용되는 엔티티는 프록시 객체로 사용하지 않고 조회한 persist 상태의 엔티티를 사용하며 매핑 중인 자식 엔티티가 존재하는 경우
삭제하지 않거나 cascade 삭제해야합니다.

#### 낙관적 락(Optimistic Locking)을 사용한다.

낙관적 락은 대부분의 경우 트랜젝션의 충돌이 일어나지 않는다는 낙관적 가정을 하는 기법입니다. 데이터베이스 레벨에서 처리하는 것이 아닌 커밋 이후 시점과 조회 시점의 version 정보와 
비교하여 일치하지 않는 경우 다른 트랜잭션에서의 변경이 일어났으므로 예외를 발생시켜 롤백하는 애플리케이션 레벨에서 처리하는 방식입니다. 충돌이 발생하면
예외가 발생하고 롤백이 발생하기 때문에 트랜잭션 충돌이 자주 발생하는 곳에는 사용이 적합하지 않습니다.

```java
@Version
private long version;
```

낙관적 락을 사용하기 위해 엔티티에 version 필드를 추가합니다. 엔티티를 대상으로 update 쿼리가 발생하는 경우
version을 증가시키고 조회 시점의 version과 비교하여 일치하지 않는 경우 OptimisticLockException 예외를 발생합니다.

```java
@SQLDelete(sql = "UPDATE posts SET deleted = true, version = version + 1 WHERE id = ? AND version = ?")
```

cascade 삭제를 위해 @SQLDelete 애노테이션을 사용한다면 마찬가지로 조건절에 version 컬럼을 추가하고 version을 증가시킵니다.

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

    if (postTx1.getComments().isEmpty()) {
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

낙관적 락에 의해 다른 트랜잭션에 의해 수정된 경우 version 정보가 일치하지 않아 예외가 발생하며 롤백을 수행합니다.

#### 비관적 락(Pessimistic Locking)을 사용한다.

비관적 락은 대부분의 경우 트랜젝션의 충돌이 일어난다는 비관적 가정을 하는 기법입니다. 데이터베이스 레벨에서 락(S, X)을 획득하여 처리하기 때문에 다른 트랜잭션들은
락을 획득하기 위해 대기하게됩니다. 데이터베이스에서 락을 처리하는 비용과 데드락이 발생할 수 있기 때문에 트랜잭션 충돌이 자주 발생하지 않는다면 사용이 적합하지 않습니다.

```java
@Version
private long version;
```

비관적 락은 데이터베이스 레벨에서 락을 획득하기 때문에 version 필드 없이도 사용할 수 있으며 락의 획득을 실패하는 경우 PessimisticLockException 예외가 발생합니다.
version 필드를 함께 사용할 필요가 있다면 version 필드를 추가합니다.

```java
Map<String, Long> queryHint = Collections.singletonMap("javax.persistence.lock.timeout", 2000L);
entityManager.find(Posts.class, id, LockModeType.PESSIMISTIC_WRITE, queryHint);
```

락을 획득하는 과정에서 데드락이 발생하게 되면 락을 획득하는 스레드가 대기 상태로 지속되어 애플리케이션 전체적인 성능에 영향을 미치면서 장애로 이어질 수 있기 때문에
반드시 락을 획득하기 위한 timeout을 설정합니다.

```java
@Test
void 트랜잭션_경합조건에_따라_삭제처리된_데이터를_매핑할수_없도록_비관적락으로_방지한다() throws Exception {
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
    
        if (postTx1.getComments().isEmpty()) {
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

비관적 락에 의해 이미 다른 트랜잭션에 의해 exclusive lock이 획득된 경우 share lock락을 획득하지 못해 순차적으로 트랜잭션이 수행됩니다. 

## 마무리
`Soft Delete`를 구현하기 위해 단순히 삭제 구분 컬럼 하나를 추가한 것만으로 잘 구현했다고 볼 수는 없습니다.
애플리케이션의 ORM 프레임워크 사용 유무에 따라 구현 방법이 달라질 수 있고 적용하면서 발생할 수 있는 문제점을 파악하고 해결해야하기 때문입니다.
전체적으로 `Soft Delete`를 사용하기보다는 적용이 필요한 부분에 한해서 사용하는 것이 좋다고 생각됩니다.

### references
- https://www.postgresql.org/docs/14/sql-vacuum.html
- https://www.postgresql.org/docs/14/sql-reindex.html
- https://www.postgresql.org/docs/14/indexes-partial.html
- https://docs.jboss.org/hibernate/orm/5.4/javadocs/org/hibernate/annotations/Where.html
- https://docs.jboss.org/hibernate/orm/5.4/javadocs/org/hibernate/annotations/SQLDelete.html
- https://www.objectdb.com/java/jpa/persistence/lock

### example 
- https://github.com/sinbom/implement-soft-delete-hibernate


