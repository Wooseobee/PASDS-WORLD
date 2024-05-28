package world.pasds.back.privateData.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import world.pasds.back.role.entity.Role;
import world.pasds.back.privateData.entity.PrivateData;
import world.pasds.back.privateData.entity.PrivateDataRole;

import java.util.List;

@Repository
public interface PrivateDataRoleRepository extends JpaRepository<PrivateDataRole, Long>, PrivateDataRoleCustomRepository {

    List<PrivateDataRole> findAllByPrivateData(PrivateData privateData);

    boolean existsByPrivateDataAndRole(PrivateData privateData, Role role);

    List<PrivateDataRole> findAllByRole(Role role);
}
