package world.pasds.back.privateData.repository.jpa;

import world.pasds.back.privateData.entity.PrivateData;
import world.pasds.back.privateData.entity.PrivateDataRole;

import java.util.List;

public interface PrivateDataRoleCustomRepository {
    List<PrivateDataRole> findAllWithRolesByPrivateData(PrivateData privateData);
}
