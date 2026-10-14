import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import static java.lang.Math.max;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@interface Id {}

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@interface Positive {
    String message() default "must be > 0";
}

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@interface MaxLength {
    int value();
}

class BankException extends Exception {
    public BankException(String message) {
        super(message);
    }
}

class InsufficientFundsException extends BankException {
    private final long shortfall;

    public InsufficientFundsException(String message, long shortfall) {
        super(message);
        this.shortfall = shortfall;
    }

    public long getShortfall() {
        return shortfall;
    }
}

class AccountNotFoundException extends BankException {
    public AccountNotFoundException(String message) {
        super(message);
    }
}

class InvalidAmountException extends BankException {
    public InvalidAmountException(String message) {
        super(message);
    }
}

class BankAuditSession implements AutoCloseable {
    private final String sessionId;

    public BankAuditSession(String sessionId) {
        this.sessionId = sessionId;
        System.out.println("Audit session opened: " + sessionId);
    }

    @Override
    public void close() {
        System.out.println("Audit session closed safely: " + sessionId);
    }
}

class AccountWorker implements Runnable {
    private Account account;
    private int times;
    private long amount;

    public AccountWorker(Account account, int times, long amount) {
        this.account = account;
        this.times = times;
        this.amount = amount;
    }

    @Override
    public void run() {
        for (int i = 0; i < times; i++) {
            try {
                account.deposit(amount);
            } catch (InvalidAmountException ignored) {
            }
        }
    }
}

class TransactionProcessor {
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, Account> sharedAccounts = new ConcurrentHashMap<>();

    public TransactionProcessor(int poolSize) {
        this.executor = Executors.newFixedThreadPool(poolSize);
    }

    public void registerAccount(Account acc) {
        sharedAccounts.put(acc.getAccountNumber(), acc);
    }

    public Account getAccount(String accNo) {
        return sharedAccounts.get(accNo);
    }

    public void submit(Runnable task) {
        executor.execute(task);
    }

    public void stop() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}

class TransactionBuffer {
    private final Queue<String> queue = new LinkedList<>();
    private final int capacity;

    public TransactionBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void produce(String tx) throws InterruptedException {
        while (queue.size() == capacity) {
            wait();
        }
        queue.add(tx);
        System.out.println("Produced transaction: " + tx);
        notifyAll();
    }

    public synchronized String consume() throws InterruptedException {
        while (queue.isEmpty()) {
            wait();
        }
        String tx = queue.poll();
        System.out.println("Consumed transaction: " + tx);
        notifyAll();
        return tx;
    }
}

class StatePersister {
    public static void save(Account[] accounts, Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file.toFile()))) {
                oos.writeObject(accounts);
            }
        } catch (Exception e) {
            System.out.println("Error saving state: " + e.getMessage());
        }
    }

    public static Account[] load(Path file) {
        if (!Files.exists(file)) {
            return new Account[0];
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file.toFile()))) {
            return (Account[]) ois.readObject();
        } catch (Exception e) {
            System.out.println("Error loading state: " + e.getMessage());
            return new Account[0];
        }
    }
}

class TransactionLog {
    public static void append(Path file, String line) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, (line + System.lineSeparator()).getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.out.println("Error appending to log: " + e.getMessage());
        }
    }
}

class ReportGenerator {
    public static void generateReport(Path logsDir, Path reportFile) {
        long totalDeposits = 0;
        long totalWithdrawals = 0;

        try {
            if (reportFile.getParent() != null) {
                Files.createDirectories(reportFile.getParent());
            }

            if (Files.exists(logsDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(logsDir)) {
                    for (Path entry : stream) {
                        if (!Files.isDirectory(entry)) {
                            try (BufferedReader reader = Files.newBufferedReader(entry)) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    String[] parts = line.trim().split("\\s+");
                                    if (parts.length >= 3) {
                                        try {
                                            long amt = Long.parseLong(parts[2]);
                                            if (parts[0].equalsIgnoreCase("DEPOSIT")) {
                                                totalDeposits += amt;
                                            } else if (parts[0].equalsIgnoreCase("WITHDRAW")) {
                                                totalWithdrawals += amt;
                                            }
                                        } catch (NumberFormatException ignored) {
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("====================================\n");
            sb.append("         END-OF-DAY REPORT          \n");
            sb.append("====================================\n");
            sb.append("Total Deposits    : Rs. ").append(totalDeposits).append("\n");
            sb.append("Total Withdrawals : Rs. ").append(totalWithdrawals).append("\n");
            sb.append("Net Cashflow      : Rs. ").append(totalDeposits - totalWithdrawals).append("\n");
            sb.append("====================================\n");

            Files.write(reportFile, sb.toString().getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.print(sb.toString());

        } catch (Exception e) {
            System.out.println("Error generating report: " + e.getMessage());
        }
    }
}

class AccountStore {
    private final Map<String, Account> accounts = new HashMap<>();
    private final List<String> transactionHistory = new ArrayList<>();

    public void add(Account account) {
        if (account != null) {
            accounts.put(account.getAccountNumber(), account);
            transactionHistory.add("Added account: " + account.getAccountNumber());
        }
    }

    public Account get(String accountNumber) {
        return accounts.get(accountNumber);
    }

    public List<Account> getAll() {
        return new ArrayList<>(accounts.values());
    }

    public void logTransaction(String log) {
        transactionHistory.add(log);
    }

    public List<String> getHistory() {
        return new ArrayList<>(transactionHistory);
    }

    public List<Account> sortedByBalance() {
        List<Account> list = new ArrayList<>(accounts.values());
        list.sort((a1, a2) -> Long.compare(a2.getBalance(), a1.getBalance()));
        return list;
    }
}

class Repository<T> {
    private final HashMap<String, T> storage = new HashMap<>();

    private String extractId(T entity) {
        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(Id.class)) {
                    field.setAccessible(true);
                    try {
                        Object val = field.get(entity);
                        return val != null ? val.toString() : null;
                    } catch (IllegalAccessException e) {
                        return null;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    public T save(T entity) {
        if (entity == null) return null;
        String id = extractId(entity);
        if (id != null) {
            storage.put(id, entity);
            return entity;
        }
        return null;
    }

    @SafeVarargs
    public final void saveAll(T... items) {
        for (T item : items) {
            save(item);
        }
    }

    public T findById(String id) {
        return storage.get(id);
    }

    public ArrayList<T> findAll() {
        return new ArrayList<>(storage.values());
    }

    public boolean delete(String id) {
        return storage.remove(id) != null;
    }

    public int count() {
        return storage.size();
    }

    public static <N extends Number> double average(N[] values) {
        if (values == null || values.length == 0) return 0.0;
        double sum = 0.0;
        for (N val : values) {
            sum += val.doubleValue();
        }
        return sum / values.length;
    }
}

record BankInfo(String name, String branch) {}

enum MenuOption {
    OPEN_ACCOUNT,
    DEPOSIT,
    WITHDRAW,
    TRANSFER,
    STATEMENT,
    LIST_ACCOUNTS,
    BATCH_PROCESS,
    RUN_REPORT,
    EXIT
}

enum TransactionType {
    DEPOSIT,
    WITHDRAW,
    TRANSFER
}

record Command(TransactionType type, String accountNumber, long amount) {}

interface Transactable {
    void deposit(long amount) throws InvalidAmountException;
    boolean withdraw(long amount) throws InsufficientFundsException, InvalidAmountException;
}

interface InterestBearing {
    double interestRate();
    default double yearlyInterest(long balance) {
        return (balance * interestRate()) / 100.0;
    }
}

interface Premium {}

@FunctionalInterface
interface WithdrawRule {
    boolean allow(Account account, long amount);
}

class AnnotationValidator {
    public static String[] validate(Object obj) {
        if (obj == null) return new String[0];
        List<String> errors = new ArrayList<>();
        Class<?> clazz = obj.getClass();

        while (clazz != null && clazz != Object.class) {
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                try {
                    Object val = field.get(obj);

                    if (field.isAnnotationPresent(Positive.class)) {
                        Positive pos = field.getAnnotation(Positive.class);
                        if (val instanceof Number num) {
                            if (num.longValue() <= 0) {
                                errors.add(pos.message());
                            }
                        }
                    }

                    if (field.isAnnotationPresent(MaxLength.class)) {
                        MaxLength ml = field.getAnnotation(MaxLength.class);
                        if (val instanceof String str) {
                            if (str.length() > ml.value()) {
                                errors.add(field.getName() + " length exceeds maximum of " + ml.value());
                            }
                        }
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return errors.toArray(new String[0]);
    }
}

class Validator {
    private static final Pattern MOBILE_PATTERN = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern IFSC_PATTERN = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    public static boolean isValidMobile(String mobile) {
        return mobile != null && MOBILE_PATTERN.matcher(mobile).matches();
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    public static boolean isValidPan(String pan) {
        return pan != null && PAN_PATTERN.matcher(pan).matches();
    }

    public static boolean isValidIfsc(String ifsc) {
        return ifsc != null && IFSC_PATTERN.matcher(ifsc).matches();
    }
}

class CommandParser {
    public static Command parse(String line) {
        if (line == null) return null;
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 3) {
            return null;
        }

        try {
            TransactionType type = TransactionType.valueOf(parts[0].toUpperCase());
            String accNo = parts[1];
            long amount = Long.parseLong(parts[2]);
            return new Command(type, accNo, amount);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

class StatementFormatter {
    public static String buildStatement(Account account) {
        StringBuilder sb = new StringBuilder();
        sb.append("====================================\n");
        sb.append("         ACCOUNT STATEMENT          \n");
        sb.append("====================================\n");
        sb.append("Account Number : ").append(account.getAccountNumber()).append("\n");
        sb.append("Account Holder : ").append(account.getOwnerName()).append("\n");
        sb.append("Account Type   : ").append(account.getClass().getSimpleName()).append("\n");
        sb.append("Current Balance: Rs. ").append(account.getBalance()).append("\n");
        sb.append("Interest Rate  : ").append(account.interestRate()).append("%\n");
        sb.append("Yearly Interest: Rs. ").append(account.yearlyInterest(account.getBalance())).append("\n");
        sb.append("Account Status : ").append(account.isActive() ? "Active" : "Inactive").append("\n");
        sb.append("====================================");
        return sb.toString();
    }
}

class Customer implements Cloneable, Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    private final String customerId;
    @MaxLength(50)
    private String name;
    private String email;
    private String mobile;
    private Address address;
    private static long customerCounter = 100;

    public static class Address implements Serializable {
        private static final long serialVersionUID = 1L;

        private String line;
        private String city;
        private String pincode;

        public Address(String line, String city, String pincode) {
            this.line = line;
            this.city = city;
            this.pincode = pincode;
        }

        public String getLine() {
            return line;
        }

        public String getCity() {
            return city;
        }

        public String getPincode() {
            return pincode;
        }

        @Override
        public String toString() {
            return line + ", " + city + " - " + pincode;
        }
    }

    private static String generateCustomerId() {
        customerCounter++;
        return "CUST" + customerCounter;
    }

    public Customer(String name, String email, String mobile, Address address) {
        this.customerId = generateCustomerId();
        this.name = name;
        this.email = email;
        this.mobile = mobile;
        this.address = address;
    }

    public Customer(String name, String email, String mobile) {
        this(name, email, mobile, null);
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getMobile() {
        return mobile;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}

abstract class Account implements Transactable, InterestBearing, Serializable, Comparable<Account> {
    private static final long serialVersionUID = 1L;

    @Id
    private final String accountNumber;
    @MaxLength(50)
    private String ownerName;
    @Positive
    protected long balance;
    private boolean active;
    private static long accountCounter = 0;

    public static void setCounter(long count) {
        accountCounter = count;
    }

    private static String generateAccountNumber() {
        accountCounter++;
        return String.format("AC%04d", accountCounter);
    }

    public Account(String ownerName, long balance) {
        this.accountNumber = generateAccountNumber();
        this.ownerName = ownerName;
        this.balance = balance;
        this.active = true;
    }

    public Account(String accountNumber, String ownerName, long balance) {
        this.accountNumber = accountNumber;
        this.ownerName = ownerName;
        this.balance = balance;
        this.active = true;
    }

    public Account(String ownerName) {
        this(ownerName, 0);
    }

    @Override
    public int compareTo(Account other) {
        return this.accountNumber.compareTo(other.accountNumber);
    }

    @Override
    public abstract double interestRate();
    public abstract boolean canWithdraw(long amount);

    @Override
    public synchronized void deposit(long amount) throws InvalidAmountException {
        if (amount <= 0) {
            throw new InvalidAmountException("Deposit amount must be greater than zero.");
        }
        balance += amount;
    }

    @Override
    public synchronized boolean withdraw(long amount) throws InsufficientFundsException, InvalidAmountException {
        if (amount <= 0) {
            throw new InvalidAmountException("Withdrawal amount must be greater than zero.");
        }
        if (!canWithdraw(amount)) {
            long shortfall = amount - balance;
            throw new InsufficientFundsException("Withdrawal failed: insufficient balance.", max(0, shortfall));
        }
        balance -= amount;
        return true;
    }

    public void transfer(Account to, long amount) throws BankException {
        if (to == null) {
            throw new AccountNotFoundException("Destination account not found.");
        }
        
        Account firstLock = this.accountNumber.compareTo(to.accountNumber) < 0 ? this : to;
        Account secondLock = this.accountNumber.compareTo(to.accountNumber) < 0 ? to : this;

        synchronized (firstLock) {
            synchronized (secondLock) {
                this.withdraw(amount);
                to.deposit(amount);
            }
        }
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public synchronized long getBalance() {
        return balance;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public String toString() {
        return accountNumber + " | " + ownerName + " | Rs. " + balance;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Account account = (Account) o;
        return Objects.equals(accountNumber, account.accountNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountNumber);
    }
}

class SavingsAccount extends Account {
    private static final long serialVersionUID = 1L;
    private long minBalance;

    public SavingsAccount(String ownerName, long balance, long minBalance) {
        super(ownerName, balance);
        this.minBalance = minBalance;
    }

    public SavingsAccount(String ownerName, long balance) {
        this(ownerName, balance, 1000);
    }

    @Override
    public double interestRate() {
        return 4.0;
    }

    @Override
    public boolean canWithdraw(long amount) {
        return (balance - amount) >= minBalance;
    }

    public long getMinBalance() {
        return minBalance;
    }
}

class CurrentAccount extends Account implements Premium {
    private static final long serialVersionUID = 1L;
    private long overdraftLimit;

    public CurrentAccount(String ownerName, long balance, long overdraftLimit) {
        super(ownerName, balance);
        this.overdraftLimit = overdraftLimit;
    }

    public CurrentAccount(String ownerName, long balance) {
        this(ownerName, balance, 5000);
    }

    @Override
    public double interestRate() {
        return 0.0;
    }

    @Override
    public boolean canWithdraw(long amount) {
        return (balance - amount) >= -overdraftLimit;
    }

    public long getOverdraftLimit() {
        return overdraftLimit;
    }
}

class FixedDepositAccount extends Account {
    private static final long serialVersionUID = 1L;

    public FixedDepositAccount(String ownerName, long balance) {
        super(ownerName, balance);
    }

    @Override
    public double interestRate() {
        return 7.0;
    }

    @Override
    public boolean canWithdraw(long amount) {
        return false;
    }
}

class BankService {
    private final Repository<Account> repository;
    private final Path logPath = Paths.get("logs", "transactions.log");

    public BankService(Repository<Account> repository) {
        this.repository = repository;
    }

    public Account openAccount(int type, String name, long openingBalance) throws BankException {
        if (openingBalance <= 0) {
            throw new InvalidAmountException("Opening balance must be positive.");
        }

        Account acc = switch (type) {
            case 1 -> new SavingsAccount(name, openingBalance);
            case 2 -> new CurrentAccount(name, openingBalance);
            case 3 -> new FixedDepositAccount(name, openingBalance);
            default -> throw new BankException("Invalid account type selected.");
        };

        repository.save(acc);
        TransactionLog.append(logPath, "OPEN " + acc.getAccountNumber() + " " + openingBalance);
        return acc;
    }

    public void deposit(String accNo, long amount) throws BankException {
        Account acc = repository.findById(accNo);
        if (acc == null) {
            throw new AccountNotFoundException("Account " + accNo + " not found.");
        }
        acc.deposit(amount);
        TransactionLog.append(logPath, "DEPOSIT " + accNo + " " + amount);
    }

    public void withdraw(String accNo, long amount) throws BankException {
        Account acc = repository.findById(accNo);
        if (acc == null) {
            throw new AccountNotFoundException("Account " + accNo + " not found.");
        }
        acc.withdraw(amount);
        TransactionLog.append(logPath, "WITHDRAW " + accNo + " " + amount);
    }

    public void transfer(String fromAccNo, String toAccNo, long amount) throws BankException {
        Account from = repository.findById(fromAccNo);
        Account to = repository.findById(toAccNo);

        if (from == null) {
            throw new AccountNotFoundException("Source account " + fromAccNo + " not found.");
        }
        if (to == null) {
            throw new AccountNotFoundException("Destination account " + toAccNo + " not found.");
        }

        from.transfer(to, amount);
        TransactionLog.append(logPath, "WITHDRAW " + fromAccNo + " " + amount);
        TransactionLog.append(logPath, "DEPOSIT " + toAccNo + " " + amount);
    }

    public String statement(String accNo) throws BankException {
        Account acc = repository.findById(accNo);
        if (acc == null) {
            throw new AccountNotFoundException("Account " + accNo + " not found.");
        }
        return StatementFormatter.buildStatement(acc);
    }

    public List<Account> listAccounts() {
        List<Account> list = repository.findAll();
        Collections.sort(list);
        return list;
    }

    public void runBatch(TransactionProcessor processor, List<Command> commands) {
        for (Account a : repository.findAll()) {
            processor.registerAccount(a);
        }

        for (Command cmd : commands) {
            processor.submit(() -> {
                try {
                    if (cmd.type() == TransactionType.DEPOSIT) {
                        deposit(cmd.accountNumber(), cmd.amount());
                    } else if (cmd.type() == TransactionType.WITHDRAW) {
                        withdraw(cmd.accountNumber(), cmd.amount());
                    }
                } catch (BankException ignored) {
                }
            });
        }
    }
}

public class MiniBank {
    private static final Path DATA_FILE = Paths.get("data", "accounts.dat");
    private static final Path LOGS_DIR = Paths.get("logs");
    private static final Path REPORT_FILE = Paths.get("reports", "daily_summary.txt");

    public static void printMenu() {
        System.out.println("\n========== MINIBANK MANAGEMENT ==========");
        System.out.println("1. Open Account");
        System.out.println("2. Deposit");
        System.out.println("3. Withdraw");
        System.out.println("4. Transfer");
        System.out.println("5. Account Statement");
        System.out.println("6. List All Accounts");
        System.out.println("7. Run Concurrent Batch Engine");
        System.out.println("8. Generate End-of-Day Report");
        System.out.println("9. Exit Application");
        System.out.print("Enter choice (1-9): ");
    }

    public static void runUnitTests(BankService service, Repository<Account> repo) {
        System.out.println("\n--- Running Built-in System Tests ---");
        try {
            Account testAcc = service.openAccount(1, "TestUser", 2000);
            service.deposit(testAcc.getAccountNumber(), 500);
            assert testAcc.getBalance() == 2500 : "Deposit test failed";
            System.out.println("[PASS] Deposit updates balance correctly.");

            boolean caught = false;
            try {
                service.withdraw(testAcc.getAccountNumber(), 10000);
            } catch (InsufficientFundsException e) {
                caught = true;
            }
            assert caught : "Overdraft test failed";
            System.out.println("[PASS] Insufficient funds properly throws exception.");

            Account concAcc = service.openAccount(1, "ConcUser", 0);
            Thread[] workers = new Thread[5];
            for (int i = 0; i < 5; i++) {
                workers[i] = new Thread(new AccountWorker(concAcc, 200, 10));
                workers[i].start();
            }
            for (Thread t : workers) {
                t.join();
            }
            assert concAcc.getBalance() == 10000 : "Thread safety test failed";
            System.out.println("[PASS] Concurrent multithreading preserves exact balance.");

            repo.delete(testAcc.getAccountNumber());
            repo.delete(concAcc.getAccountNumber());
            System.out.println("[INFO] All unit test assertions passed.\n");
        } catch (Exception e) {
            System.out.println("[FAIL] Unit test failure: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        BankInfo info = new BankInfo("Charusat National Bank", "Changa Branch");
        System.out.println("MiniBank Online Banking - " + info.name() + " (" + info.branch() + ")");

        Repository<Account> repository = new Repository<>();

        Account[] loaded = StatePersister.load(DATA_FILE);
        if (loaded.length > 0) {
            repository.saveAll(loaded);
            Account.setCounter(loaded.length);
            System.out.println("Restored " + loaded.length + " accounts from persistence file.");
        } else {
            SavingsAccount sa = new SavingsAccount("Shivam", 5000, 1000);
            CurrentAccount ca = new CurrentAccount("Riya", 8000, 3000);
            repository.saveAll(sa, ca);
            System.out.println("Initialized default repository accounts.");
        }

        BankService service = new BankService(repository);
        runUnitTests(service, repository);

        Scanner sc = new Scanner(System.in);
        boolean running = true;

        while (running) {
            printMenu();
            if (!sc.hasNextInt()) {
                System.out.println("Invalid numeric input. Please try again.");
                sc.next();
                continue;
            }

            int choice = sc.nextInt();
            sc.nextLine();

            MenuOption opt = switch (choice) {
                case 1 -> MenuOption.OPEN_ACCOUNT;
                case 2 -> MenuOption.DEPOSIT;
                case 3 -> MenuOption.WITHDRAW;
                case 4 -> MenuOption.TRANSFER;
                case 5 -> MenuOption.STATEMENT;
                case 6 -> MenuOption.LIST_ACCOUNTS;
                case 7 -> MenuOption.BATCH_PROCESS;
                case 8 -> MenuOption.RUN_REPORT;
                case 9 -> MenuOption.EXIT;
                default -> null;
            };

            if (opt == null) {
                System.out.println("Invalid menu choice. Select between 1 and 9.");
                continue;
            }

            try {
                switch (opt) {
                    case OPEN_ACCOUNT -> {
                        System.out.println("\nSelect Account Type: 1. Savings | 2. Current | 3. Fixed Deposit");
                        int type = sc.nextInt();
                        sc.nextLine();
                        System.out.print("Enter customer name: ");
                        String name = sc.nextLine().trim();
                        System.out.print("Enter opening balance: ");
                        long bal = sc.nextLong();
                        Account created = service.openAccount(type, name, bal);
                        System.out.println("Account opened successfully: " + created);
                    }
                    case DEPOSIT -> {
                        System.out.print("Enter Account Number: ");
                        String accNo = sc.nextLine().trim().toUpperCase();
                        System.out.print("Enter deposit amount: ");
                        long amt = sc.nextLong();
                        service.deposit(accNo, amt);
                        System.out.println("Deposit of Rs. " + amt + " completed.");
                    }
                    case WITHDRAW -> {
                        System.out.print("Enter Account Number: ");
                        String accNo = sc.nextLine().trim().toUpperCase();
                        System.out.print("Enter withdrawal amount: ");
                        long amt = sc.nextLong();
                        service.withdraw(accNo, amt);
                        System.out.println("Withdrawal of Rs. " + amt + " completed.");
                    }
                    case TRANSFER -> {
                        System.out.print("Enter Source Account Number: ");
                        String from = sc.nextLine().trim().toUpperCase();
                        System.out.print("Enter Destination Account Number: ");
                        String to = sc.nextLine().trim().toUpperCase();
                        System.out.print("Enter transfer amount: ");
                        long amt = sc.nextLong();
                        service.transfer(from, to, amt);
                        System.out.println("Transfer of Rs. " + amt + " successful.");
                    }
                    case STATEMENT -> {
                        System.out.print("Enter Account Number: ");
                        String accNo = sc.nextLine().trim().toUpperCase();
                        System.out.println(service.statement(accNo));
                    }
                    case LIST_ACCOUNTS -> {
                        System.out.println("\n--- All Registered Bank Accounts ---");
                        List<Account> accounts = service.listAccounts();
                        if (accounts.isEmpty()) {
                            System.out.println("No accounts found.");
                        } else {
                            for (Account a : accounts) {
                                System.out.println(a);
                            }
                        }
                    }
                    case BATCH_PROCESS -> {
                        System.out.println("Executing multithreaded transaction batch via thread pool...");
                        TransactionProcessor tp = new TransactionProcessor(4);
                        List<Command> batch = new ArrayList<>();
                        List<Account> current = service.listAccounts();
                        if (!current.isEmpty()) {
                            String target = current.get(0).getAccountNumber();
                            batch.add(new Command(TransactionType.DEPOSIT, target, 200));
                            batch.add(new Command(TransactionType.DEPOSIT, target, 300));
                            batch.add(new Command(TransactionType.WITHDRAW, target, 100));
                        }
                        service.runBatch(tp, batch);
                        tp.stop();
                        System.out.println("Batch processing successfully finished.");
                    }
                    case RUN_REPORT -> {
                        System.out.println("\nGenerating End-of-Day audit report from NIO directory logs:");
                        ReportGenerator.generateReport(LOGS_DIR, REPORT_FILE);
                    }
                    case EXIT -> {
                        List<Account> all = repository.findAll();
                        StatePersister.save(all.toArray(new Account[0]), DATA_FILE);
                        System.out.println("Persisted " + all.size() + " accounts to " + DATA_FILE);
                        System.out.println("Thank you for using MiniBank. System terminated.");
                        running = false;
                    }
                }
            } catch (InsufficientFundsException e) {
                System.out.println("Transaction Error: " + e.getMessage() + " (Shortfall: Rs. " + e.getShortfall() + ")");
            } catch (BankException e) {
                System.out.println("Banking Error: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("Unexpected system error: " + e.getMessage());
            }
        }
        sc.close();
    }
}