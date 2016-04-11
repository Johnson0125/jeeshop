package org.rembx.jeeshop.user;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.rembx.jeeshop.mail.Mailer;
import org.rembx.jeeshop.rest.WebApplicationException;
import org.rembx.jeeshop.role.JeeshopRoles;
import org.rembx.jeeshop.user.model.Address;
import org.rembx.jeeshop.user.model.User;
import org.rembx.jeeshop.user.model.UserPersistenceUnit;
import org.rembx.jeeshop.user.test.TestMails;
import org.rembx.jeeshop.user.test.TestUser;
import sun.security.acl.PrincipalImpl;

import javax.ejb.SessionContext;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import javax.ws.rs.core.Response;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.*;
import static org.rembx.jeeshop.user.tools.CryptTools.hashSha256Base64;


public class UsersCT {

    private Users service;

    private TestUser testUser;
    private TestMails testMails;
    private static EntityManagerFactory entityManagerFactory;
    private EntityManager entityManager;
    private Mailer mailerMock;
    private SessionContext sessionContextMock;

    @BeforeClass
    public static void beforeClass() {
        entityManagerFactory = Persistence.createEntityManagerFactory(UserPersistenceUnit.NAME);
    }

    @Before
    public void setup() {
        testUser = TestUser.getInstance();
        testMails = TestMails.getInstance();
        entityManager = entityManagerFactory.createEntityManager();
        mailerMock = mock(Mailer.class);
        sessionContextMock = mock(SessionContext.class);
        service = new Users(entityManager, new UserFinder(entityManager), new RoleFinder(entityManager),
                new CountryChecker("FRA,GBR"), new MailTemplateFinder(entityManager), mailerMock, sessionContextMock);
    }

    @Test
    public void findAll_shouldReturnNoneEmptyList() {
        assertThat(service.findAll(null, null, null, null, null)).isNotEmpty();
    }

    @Test
    public void findAll_withPagination_shouldReturnNoneEmptyListPaginated() {
        List<User> users = service.findAll(null, 0, 1, null, null);
        assertThat(users).isNotEmpty();
        assertThat(users).containsExactly(testUser.firstUser());
    }

    @Test
    public void findAll_ByLogin_shouldReturnSearchedUser() {
        List<User> users = service.findAll(testUser.firstUser().getLogin(), 0, 1, null, null);
        assertThat(users).isNotEmpty();
        assertThat(users).containsExactly(testUser.firstUser());
    }


    @Test
    public void findAll_ByFirstName_shouldReturnSearchedUser() {
        List<User> users = service.findAll(testUser.firstUser().getFirstname(), 0, 1, null, null);
        assertThat(users).isNotEmpty();
        assertThat(users).containsExactly(testUser.firstUser());
    }

    @Test
    public void findAll_ByLastName_shouldReturnSearchedUser() {
        List<User> users = service.findAll(testUser.firstUser().getLastname(), 0, 1, null, null);
        assertThat(users).isNotEmpty();
        assertThat(users).containsExactly(testUser.firstUser());
    }

    @Test
    public void find() throws Exception {
        assertThat(service.find(1L)).isNotNull();
    }

    @Test
    public void find_withUnknownId_ShouldThrowException() throws Exception {
        try {
            service.find(999L);
            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.NOT_FOUND);
        }
    }

    @Test
    public void count() {
        assertThat(service.count(null)).isGreaterThan(0);
    }


    @Test
    public void count_withUnknownSearchCriteria() {
        assertThat(service.count("unknown")).isEqualTo(0);
    }

    @Test
    public void create_shouldThrowBadRequestExWhenUserHasAnId() throws Exception {

        User user = new User();
        user.setId(777L);

        try {
            service.create(user);
            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.BAD_REQUEST);
        }
    }

    @Test
    public void create_shouldThrowConflictExWhenUserWithGivenLoginAlreadyExists() throws Exception {

        User user = new User();
        user.setLogin("test@test.com");

        try {
            service.create(user);
            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.CONFLICT);
        }
    }

    @Test
    public void create_shouldThrowBadRequestExceptionExWhenUsersCountryIsNotAvailable() throws Exception {

        User user = new User();
        user.setLogin("toto@toto.com");
        Address address = new Address();
        address.setCountryIso3Code("ZZZ");
        user.setAddress(address);

        try {
            service.create(user);
            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.BAD_REQUEST);
        }
    }

    @Test
    public void create_shouldThrowBadRequestExceptionExWhenUserAddressHasAnId() throws Exception {

        User user = new User();
        user.setLogin("toto@toto.com");
        Address address = new Address();
        address.setId(1L);
        user.setAddress(address);

        try {
            service.create(user);
            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.BAD_REQUEST);
        }
    }

    @Test
    public void create_shouldOnlyPersistUserWhenOperationIsTriggeredByAdminUser() throws Exception {

        User user = new User("register2@test.com", "test", "John", "Doe", "+33616161616", null, new Date(), "fr_FR", null);
        user.setGender("M.");
        when(sessionContextMock.isCallerInRole(JeeshopRoles.ADMIN)).thenReturn(true);

        entityManager.getTransaction().begin();
        service.create(user);
        entityManager.getTransaction().commit();

        verify(sessionContextMock).isCallerInRole(JeeshopRoles.ADMIN);

        assertThat(entityManager.find(User.class, user.getId())).isNotNull();

        removeTestUser(user);
    }

    @Test
    public void create_shouldPersistEndUserAndRetrieveUserRegistrationMailTemplateAndSendMail() throws Exception {

        User user = new User("register3@test.com", "test", "John", "Doe", "+33616161616", null, new Date(), "fr_FR", null);
        user.setGender("M.");

        when(sessionContextMock.isCallerInRole(JeeshopRoles.ADMIN)).thenReturn(false);

        entityManager.getTransaction().begin();
        service.create(user);
        entityManager.getTransaction().commit();

        verify(sessionContextMock).isCallerInRole(JeeshopRoles.ADMIN);
        verify(mailerMock).sendMail(testMails.userRegistrationMailTemplate().getSubject(), user.getLogin(), "<html><body>Welcome M. John Doe</body></html>");

        assertThat(entityManager.find(User.class, user.getId())).isNotNull();
        assertThat(user.getActivated()).isFalse();
        assertThat(user.getActionToken()).isNotNull();

        entityManager.remove(user);
    }

    @Test
    public void create_shouldJustPersistEndUserEvenWhenExceptionDuringMailSending() throws Exception {

        User user = new User("register1@test.com", "test", "John", "Doe", "+33616161616", null, new Date(), "fr_FR", null);
        user.setGender("M.");

        Address address = new Address("7 blue street", "Nowhere", "00001", "John", "Doe", "M.", null, "FRA");

        user.setAddress(address);

        when(sessionContextMock.isCallerInRole(JeeshopRoles.ADMIN)).thenReturn(false);

        entityManager.getTransaction().begin();
        service.create(user);
        entityManager.getTransaction().commit();

        doThrow(new IllegalStateException("Test Exception")).when(mailerMock).sendMail(testMails.userRegistrationMailTemplate().getSubject(), user.getLogin(), testMails.userRegistrationMailTemplate().getContent());

        verify(sessionContextMock).isCallerInRole(JeeshopRoles.ADMIN);
        verify(mailerMock).sendMail(testMails.userRegistrationMailTemplate().getSubject(), user.getLogin(), "<html><body>Welcome M. John Doe</body></html>");

        final User persistedUser = entityManager.find(User.class, user.getId());
        assertThat(persistedUser).isNotNull();
        assertThat(persistedUser.getAddress()).isEqualTo(address);

        entityManager.remove(user);
    }

    @Test
    public void activate_shouldActivateUserAndClearActionToken() throws Exception {

        User user = new User("activate1@test.com", "test", "John", "Doe", "+33616161616", null, new Date(), "fr_FR", null);
        user.setGender("M.");

        final UUID actionToken = UUID.randomUUID();
        user.setActionToken(actionToken);
        user.setActivated(false);

        entityManager.getTransaction().begin();
        entityManager.persist(user);
        entityManager.getTransaction().commit();

        service.activate(user.getLogin(), actionToken.toString());

        final User modifiedUser = entityManager.find(User.class, user.getId());
        assertThat(modifiedUser).isNotNull();
        assertThat(modifiedUser.getActivated()).isTrue();
        assertThat(modifiedUser.getActionToken()).isNull();

    }


    @Test
    public void activate_shouldThrowNotFoundExWhenUserIsNotFound() throws Exception {

        try {
            service.activate("unknown_login", UUID.randomUUID().toString());
            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.NOT_FOUND);
        }

    }


    @Test
    public void sendResetPasswordMail_shouldGenerateActionTokenAndSendResetPasswordMail() throws Exception {

        entityManager.getTransaction().begin();
        service.sendResetPasswordMail(testUser.firstUser().getLogin());
        entityManager.getTransaction().commit();

        verify(mailerMock).sendMail(testMails.resetPasswordMailTemplate().getSubject(), testUser.firstUser().getLogin(), "<html><body>Here is the link to reset your password</body></html>");

        User persistedUser = entityManager.find(User.class, testUser.firstUser().getId());
        assertThat(persistedUser).isNotNull();

        entityManager.getTransaction().begin();
        persistedUser.setActionToken(null);
        entityManager.getTransaction().commit();

    }

    @Test
    public void sendResetPasswordMail_shouldThrowNotFoundEX_WhenUserIsNotFound() throws Exception {

        try {
            service.sendResetPasswordMail("unknown_login");
            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.NOT_FOUND);
        }

    }

    @Test
    public void resetPassword_shouldUpdateUserPasswordWhenActionTokenMatchesUserActionToken() throws Exception {

        User user = notActivatedTestUser();

        service.resetPassword(user.getLogin(), user.getActionToken().toString(), "newPassword");

        final User updatedUser = entityManager.find(User.class, user.getId());
        assertThat(updatedUser).isNotNull();
        assertThat(updatedUser.getPassword()).isEqualTo(hashSha256Base64("newPassword"));
        assertThat(updatedUser.getActionToken()).isNull();

        verify(mailerMock).sendMail(testMails.changePasswordMailTpl().getSubject(), user.getLogin(), testMails.changePasswordMailTpl().getContent());

        removeTestUser(user);

    }

    @Test
    public void resetPassword_shouldUpdateUserPasswordForAuthenticatedUser() throws Exception {

        User user = notActivatedTestUser();

        when(sessionContextMock.isCallerInRole(JeeshopRoles.USER)).thenReturn(true);
        when(sessionContextMock.getCallerPrincipal()).thenReturn(new PrincipalImpl(user.getLogin()));
        service.resetPassword(user.getLogin(), null, "newPassword");

        final User updatedUser = entityManager.find(User.class, user.getId());
        assertThat(updatedUser).isNotNull();
        assertThat(updatedUser.getPassword()).isEqualTo(hashSha256Base64("newPassword"));

        verify(mailerMock).sendMail(testMails.changePasswordMailTpl().getSubject(), user.getLogin(), testMails.changePasswordMailTpl().getContent());

        removeTestUser(user);
    }

    @Test
    public void resetPassword_shouldUpdateUserPasswordForAuthenticatedADMINUser() throws Exception {

        User user = notActivatedTestUser();

        when(sessionContextMock.isCallerInRole(JeeshopRoles.ADMIN)).thenReturn(true);
        service.resetPassword(user.getLogin(), null, "newPassword");

        final User updatedUser = entityManager.find(User.class, user.getId());
        assertThat(updatedUser).isNotNull();
        assertThat(updatedUser.getPassword()).isEqualTo(hashSha256Base64("newPassword"));

        verify(mailerMock).sendMail(testMails.changePasswordMailTpl().getSubject(), user.getLogin(), testMails.changePasswordMailTpl().getContent());

        removeTestUser(user);
    }

    @Test
    public void resetPassword_shouldReturnNotFoundResponse_whenUserIsNotFound() throws Exception {

        try {
            service.resetPassword("unknown_login", null, null);
            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.NOT_FOUND);
        }
    }

    @Test
    public void resetPassword_shouldReturnUnauthorizedResponse_whenAuthenticatedUserDoesNotMatchLogin() throws Exception {

        try {
            when(sessionContextMock.isCallerInRole(JeeshopRoles.USER)).thenReturn(true);
            when(sessionContextMock.getCallerPrincipal()).thenReturn(new PrincipalImpl(testUser.firstUser().getLogin()));

            service.resetPassword("not_matching_login", null, null);
            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.UNAUTHORIZED);
        }
    }

    @Test
    public void modifyUser_ShouldModifyUser() {
        User detachedUserToModify = new User("test2@test.com", "test", "John", "Doe", "+33616161616", null, new Date(), "fr_FR", null);

        detachedUserToModify.setId(testUser.firstUser().getId());

        service.modify(detachedUserToModify);

    }

    @Test
    public void modifyUnknownUser_ShouldThrowNotFoundException() {
        User detachedUser = new User("test3@test.com", "test", "John", "Doe", "+33616161616", null, new Date(), "fr_FR", null);

        detachedUser.setId(9999L);
        try {
            service.modify(detachedUser);
            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.NOT_FOUND);
        }
    }

    @Test
    public void modify_ShouldThrowUnauthorizedError_WhenAuthenticatedUserDoesNotMatchLogin() throws Exception {

        User detachedUserToModify = new User("test2@test.com", "test", "John", "Doe", "+33616161616", null, new Date(), "fr_FR", null);

        try {
            when(sessionContextMock.isCallerInRole(JeeshopRoles.USER)).thenReturn(true);
            when(sessionContextMock.getCallerPrincipal()).thenReturn(new PrincipalImpl(testUser.firstUser().getLogin()));

            service.modify(detachedUserToModify);

            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.UNAUTHORIZED);
        }
    }

    @Test
    public void delete_shouldRemove() {
        entityManager.getTransaction().begin();
        User user = new User("test4@test.com", "test", "John", "Doe", "+33616161616", null, new Date(), "fr_FR", null);
        user.setGender("M.");
        entityManager.persist(user);
        entityManager.getTransaction().commit();

        entityManager.getTransaction().begin();
        service.delete(user.getId());
        entityManager.getTransaction().commit();

        assertThat(entityManager.find(User.class, user.getId())).isNull();
    }

    @Test
    public void delete_NotExistingEntry_shouldThrowNotFoundEx() {
        try {
            entityManager.getTransaction().begin();
            service.delete(666L);
            entityManager.getTransaction().commit();
            fail("should have thrown ex");
        } catch (WebApplicationException e) {
            assertThat(e.getResponse().getStatusInfo()).isEqualTo(Response.Status.NOT_FOUND);
        }
    }

    @Test
    public void findCurrentUser_shouldReturnCurrentAuthenticatedUser() throws Exception {
        when(sessionContextMock.getCallerPrincipal()).thenReturn(new PrincipalImpl(testUser.firstUser().getLogin()));

        assertThat(service.findCurrentUser()).isEqualTo(testUser.firstUser());
    }

    private User notActivatedTestUser() {
        User user = new User("reset1@test.com", "password", "John", "Doe", "+33616161616", null, new Date(), "fr_FR", null);
        user.setGender("M.");

        final UUID actionToken = UUID.randomUUID();
        user.setActionToken(actionToken);
        user.setActivated(false);

        entityManager.getTransaction().begin();
        entityManager.persist(user);
        entityManager.getTransaction().commit();
        return user;
    }

    private void removeTestUser(User user) {
        entityManager.getTransaction().begin();
        entityManager.remove(user);
        entityManager.getTransaction().commit();
    }

}
