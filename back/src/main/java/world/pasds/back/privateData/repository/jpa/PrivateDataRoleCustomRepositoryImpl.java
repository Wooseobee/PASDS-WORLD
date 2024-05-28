package world.pasds.back.privateData.repository.jpa;

import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import world.pasds.back.privateData.entity.PrivateData;
import world.pasds.back.privateData.entity.PrivateDataRole;
import world.pasds.back.privateData.entity.QPrivateDataRole;
import world.pasds.back.role.entity.QRole;

import java.util.List;

@RequiredArgsConstructor
public class PrivateDataRoleCustomRepositoryImpl implements PrivateDataRoleCustomRepository{

    private final JPAQueryFactory queryFactory;

    @Override
    public List<PrivateDataRole> findAllWithRolesByPrivateData(PrivateData privateData) {
        QPrivateDataRole privateDataRole = QPrivateDataRole.privateDataRole;
        QRole role = QRole.role;
        return queryFactory.selectFrom(privateDataRole)
                .join(privateDataRole.role, role).fetchJoin()
                .where(privateDataRole.privateData.eq(privateData))
                .fetch();
    }
}
