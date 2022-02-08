# JPA + Hibernate 기반의 개발 환경에서 Soft Delete 구현하기

## Soft Delete란?

Soft Delete 또는 Logical Delete는 delete 쿼리를 사용하여 물리적으로 데이터를 삭제하는 것이 아니라
update 쿼리를 통해 상태를 변경하여 삭제된 데이터로 구분할 수 있도록 논리적으로 데이터를 삭제하는 것을 의미합니다.

삭제된 데이터로 구분할 수 있는 방법은 다음과 같습니다.
- delete_flag(boolean, int) 컬럼의 값이 true 또는 1인 경우
- delete_at(timestamp) 컬럼의 값이 현재 timestamp보다 이전인 경우

Soft Delete는 물리적인 데이터 삭제가 부담되거나 삭제된 데이터들을 보관하여 데이터로써 활용할 필요나 가치가 있는 경우에 사용됩니다.

## Soft Delete & Hard Delete 비교

Soft Delete와 Hard Delete를 사용했을 때 여러가지 관점에서 비교를 해보겠습니다.

| |Soft Delete|Hard Delete|comment|
|---|-----|-----|-----|
|삭제|UPDATE table SET delete_flag = true WHERE id = ?|DELETE table FROM WHERE id = ?|Soft Delete는 삭제 구분 값을 수정하여 논리적으로 데이터를 삭제 처리합니다.|
|조회|SELECT * FROM table WHERE delete_flag = false|SELECT * FROM table|Soft Delete는 삭제 처리된 데이터가 포함되어 존재하기 때문에 모든 조회 쿼리에 delete_flag = false 조건이 필요합니다.|
|복원|update 쿼리로 삭제 구분 값을 변경하여 복원합니다.|백업 또는 쿼리 로그를 통해 복원합니다.|Hard Delete는 백업과 장애 발생 시점의 간격과 쿼리 로그 유무에 따라 복원이 어려울 수 있습니다.|
|용량|삭제시 테이블의 용량이 감소하지 않습니다.|삭제시 테이블의 용량이 감소합니다.|Soft Delete는 데이터가 물리적으로 삭제되지 않기 때문에 지속적으로 테이블의 용량이 증가합니다.|
|unique index|삭제 처리된 값을 필터링 할 수 있는 partial index를 사용하여 unique index를 생성합니다. 만약 지원하지 않는 DBMS를 사용하는 경우 삭제된 데이터와 값이 중복되어 unique 제약조건을 위반할 수 있으므로 삭제 구분 컬럼으로 delete_at(timestamp)을 사용하고 index 구성에 포함시킵니다.|중복될 수 없는 값들로 unique index를 구성합니다.|Sofe Delete는 partial index를 사용할 수 없는 경우 삭제 처리된 데이터가 인덱스에 포함됩니다.
|on delete cascade|삭제 구분 값을 수정하여 삭제 처리하기 때문에 발생하지 않습니다. 애플리케이션에서 쿼리를 발생시켜 참조 테이블을 삭제 처리하거나 데이터베이스의 트리거를 사용해야합니다.|삭제시 설정된 cascade가 발생합니다.|Soft Delete는 삭제 처리시 발생하는 cascade를 직접 구현해야합니다.|

TODO 더 설명할 필요가 있는 내용 추가

## JPA + Hibernate 개발 환경에서의 구현
### 구현 방법
### 발생할 수 있는 문제점
### 해결방안
#### @NotFound With Eager Fetch Join
#### Locking(애플리케이션 락, 분산 락, 데이터베이스 락)


## 마무리

TODO 반드시 Soft Delete를 사용하는 것보다는 상황과 필요에 따라 Soft Delete를 사용하는 것이 좋다는 내용.