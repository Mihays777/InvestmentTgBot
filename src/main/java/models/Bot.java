package models;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.io.FileWriter;
import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

public class Bot extends TelegramLongPollingBot {

    // Поля класса
    private Connection connection;
    private Map<String, String> userStates = new HashMap<>(); // Состояния пользователей
    private Map<Long, Map<String, String>> tempDataMap = new HashMap<>(); // Временные данные пользователей
    private ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10); // Планировщик задач
    private Map<String, ScheduledFuture<?>> meetingTasks = new HashMap<>(); // Задачи встреч
    private Map<Long, Integer> lastMessageIdMap = new HashMap<>(); // ID последних сообщений для редактирования

    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Основной метод обработки обновлений
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                handleMessage(update);
            } else if (update.hasCallbackQuery()) {
                handleCallbackQuery(update);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Обработка текстовых сообщений
    private void handleMessage(Update update) {
        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();
        String chatIdStr = chatId.toString();

        String state = userStates.get(chatIdStr);

        // Обработка команд меню
        if (text.equals("/start")) {
            showWelcomeScreen(chatId);
            return;
        } else if (text.equals("/menu")) {
            showMainMenu(chatId);
            return;
        }

        // Обработка состояний пользователя
        if (state != null) {
            switch (state) {
                case "ожидание имени":
                    handleNameInput(chatId, text);
                    break;

                case "ожидание телефона":
                    handlePhoneInput(chatId, text);
                    break;

                case "ожидание города":
                    handleCityInput(chatId, text);
                    break;

                case "ожидание даты звонка":
                    handleCallDateInput(chatId, text);
                    break;

                case "поиск клиента для звонка":
                    searchClientForMeeting(chatId, text);
                    return;

                case "изменение телефона":
                    searchClientForEdit(chatId, text);
                    return;

                case "удаление телефона":
                    searchClientForDelete(chatId, text);
                    return;

                case "изменение имени":
                case "изменение телефона ввод":
                case "изменение города":
                    handleTextParameterChange(chatId, text);
                    break;

                case "отложить дни":
                    try {
                        int days = Integer.parseInt(text);
                        String meetingKey = userStates.get(chatIdStr + "_postpone");
                        postponeMeeting(chatId, meetingKey, days, 0, 0);
                        userStates.remove(chatIdStr);
                        userStates.remove(chatIdStr + "_postpone");
                    } catch (NumberFormatException e) {
                        editMessage(chatId, "Введите число дней:");
                    }
                    break;

                case "отложить часы":
                    try {
                        int hours = Integer.parseInt(text);
                        String meetingKey = userStates.get(chatIdStr + "_postpone");
                        postponeMeeting(chatId, meetingKey, 0, hours, 0);
                        userStates.remove(chatIdStr);
                        userStates.remove(chatIdStr + "_postpone");
                    } catch (NumberFormatException e) {
                        editMessage(chatId, "Введите число часов:");
                    }
                    break;

                case "отложить минуты":
                    try {
                        int minutes = Integer.parseInt(text);
                        String meetingKey = userStates.get(chatIdStr + "_postpone");
                        postponeMeeting(chatId, meetingKey, 0, 0, minutes);
                        userStates.remove(chatIdStr);
                        userStates.remove(chatIdStr + "_postpone");
                    } catch (NumberFormatException e) {
                        editMessage(chatId, "Введите число минут:");
                    }
                    break;

                case "звонок дата":
                    try {
                        int clientIdForMeeting = Integer.parseInt(userStates.get(chatIdStr + "_meeting_client"));
                        LocalDateTime newTime = LocalDateTime.parse(text + ":00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        if (newTime.isBefore(LocalDateTime.now())) {
                            editMessage(chatId, "Дата звонка не может быть в прошлом! Введите новую дату и время (формат: ГГГГ-ММ-ДД ЧЧ:ММ):");
                        } else {
                            updateMeetingTime(chatId, clientIdForMeeting, text);
                            userStates.remove(chatIdStr);
                            userStates.remove(chatIdStr + "_meeting_client");
                        }
                    } catch (Exception e) {
                        editMessage(chatId, "Неверный формат даты! Используйте формат: ГГГГ-ММ-ДД ЧЧ:ММ");
                    }
                    break;

                default:
                    showMainMenu(chatId);
                    return;
            }
        } else {
            showMainMenu(chatId);
            return;
        }
    }

    // Обработка ввода имени
    private void handleNameInput(Long chatId, String name) {
        String chatIdStr = chatId.toString();
        Map<String, String> clientData = new HashMap<>();
        clientData.put("name", name);
        tempDataMap.put(chatId, clientData);

        editMessage(chatId,
                "👤 Имя клиента: " + name + "\n\n" +
                        "📱 Введите номер телефона клиента (формат: 81234567890):");
        userStates.put(chatIdStr, "ожидание телефона");
    }

    // Проверка, занят ли номер телефона
    private boolean isPhoneNumberTaken(String phone) {
        initializeDatabase();

        String sql = "SELECT COUNT(*) as count FROM clients WHERE phone = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, phone);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return rs.getInt("count") > 0;
            }
        } catch (SQLException e) {
            System.out.println("Ошибка при проверке номера телефона: " + e.getMessage());
            e.printStackTrace();
        }

        return false;
    }

    // Проверка, занят ли номер телефона другим клиентом (кроме указанного)
    private boolean isPhoneNumberTaken(String phone, int excludeClientId) {
        initializeDatabase();

        String sql = "SELECT COUNT(*) as count FROM clients WHERE phone = ? AND id != ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, phone);
            pstmt.setInt(2, excludeClientId);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return rs.getInt("count") > 0;
            }
        } catch (SQLException e) {
            System.out.println("Ошибка при проверке номера телефона: " + e.getMessage());
            e.printStackTrace();
        }

        return false;
    }

    // Обработка ввода телефона с валидацией
    private void handlePhoneInput(Long chatId, String phone) {
        String chatIdStr = chatId.toString();

        // Валидация телефона
        if (!phone.matches("^8\\d{10}$")) {
            editMessage(chatId,
                    "❌ Неверный формат телефона!\n\n" +
                            "Номер должен:\n" +
                            "• Начинаться с цифры 8\n" +
                            "• Содержать 11 цифр\n" +
                            "• Формат: 81234567890\n\n" +
                            "Пожалуйста, введите номер телефона еще раз:");
            return;
        }

        // Проверяем, не занят ли номер другим клиентом
        if (isPhoneNumberTaken(phone)) {
            editMessage(chatId,
                    "❌ Этот номер телефона уже используется другим клиентом!\n\n" +
                            "Пожалуйста, введите другой номер телефона:");
            return;
        }

        tempDataMap.get(chatId).put("phone", phone);
        editMessage(chatId,
                "👤 Имя клиента: " + tempDataMap.get(chatId).get("name") + "\n" +
                        "📱 Телефон: " + phone + "\n\n" +
                        "🏙️ Введите город для поиска недвижимости:");
        userStates.put(chatIdStr, "ожидание города");
    }

    // Обработка ввода города
    private void handleCityInput(Long chatId, String city) {
        String chatIdStr = chatId.toString();
        tempDataMap.get(chatId).put("city", city);

        editMessage(chatId,
                "👤 Имя клиента: " + tempDataMap.get(chatId).get("name") + "\n" +
                        "📱 Телефон: " + tempDataMap.get(chatId).get("phone") + "\n" +
                        "🏙️ Город: " + city + "\n\n" +
                        "Выберите тип недвижимости:");
        showPropertyTypeSelection(chatId, "create");
    }

    // Обработка ввода даты звонка
    private void handleCallDateInput(Long chatId, String dateTime) {
        String chatIdStr = chatId.toString();
        try {
            LocalDateTime meetingTime = LocalDateTime.parse(dateTime + ":00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            if (meetingTime.isBefore(LocalDateTime.now())) {
                editMessage(chatId, "Дата звонка не может быть в прошлом! Введите дату и время звонка (формат: ГГГГ-ММ-ДД ЧЧ:ММ):");
            } else {
                Map<String, String> data = tempDataMap.get(chatId);
                String name = data.get("name");
                String phone = data.get("phone");
                String city = data.get("city");
                String propertyType = data.get("type");

                saveClientToDatabase(name, phone, city, propertyType, dateTime);
                scheduleMeetingNotifications(chatId, name, phone, dateTime);

                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("✅ Клиент " + name + " успешно создан!\n" +
                        "📅 Звонок назначен на: " + dateTime + "\n\n" +
                        "Вы получите уведомления о звонке.");
                message.setReplyMarkup(getBackToMenuKeyboard());
                execute(message);

                userStates.remove(chatIdStr);
                tempDataMap.remove(chatId);
            }
        } catch (Exception e) {
            editMessage(chatId, "Неверный формат даты! Используйте формат: ГГГГ-ММ-ДД ЧЧ:ММ\nНапример: 2025-12-25 14:30");
        }
    }

    // Обработка изменения текстовых параметров
    private void handleTextParameterChange(Long chatId, String text) {
        String chatIdStr = chatId.toString();
        String state = userStates.get(chatIdStr);
        String param = "";

        if (state.equals("изменение имени")) {
            param = "name";
        } else if (state.equals("изменение телефона ввод")) {
            // Валидация телефона
            if (!text.matches("^8\\d{10}$")) {
                editMessage(chatId,
                        "❌ Неверный формат телефона!\n\n" +
                                "Номер должен:\n" +
                                "• Начинаться с цифры 8\n" +
                                "• Содержать 11 цифр\n" +
                                "• Формат: 81234567890\n\n" +
                                "Пожалуйста, введите номер телефона еще раз:");
                return;
            }

            // Получаем ID текущего клиента
            int clientId = Integer.parseInt(userStates.get(chatIdStr + "_clientId"));

            // Проверяем, не занят ли номер другим клиентом (кроме текущего)
            if (isPhoneNumberTaken(text, clientId)) {
                editMessage(chatId,
                        "❌ Этот номер телефона уже используется другим клиентом!\n\n" +
                                "Пожалуйста, введите другой номер телефона:");
                return;
            }

            param = "phone";
        } else if (state.equals("изменение города")) {
            param = "city";
        }

        int clientId = Integer.parseInt(userStates.get(chatIdStr + "_clientId"));
        updateClientParameter(chatId, clientId, param, text);
        userStates.remove(chatIdStr);
        userStates.remove(chatIdStr + "_clientId");
    }

    // Обработка callback-запросов (нажатия на кнопки)
    private void handleCallbackQuery(Update update) {
        String callbackData = update.getCallbackQuery().getData();
        Long chatId = update.getCallbackQuery().getMessage().getChatId();
        String chatIdStr = chatId.toString();

        try {
            // Сохраняем ID сообщения для последующего редактирования
            int messageId = update.getCallbackQuery().getMessage().getMessageId();
            lastMessageIdMap.put(chatId, messageId);

            if (callbackData.equals("добавить клиента")) {
                SendMessage message = new SendMessage();
                message.setChatId(chatIdStr);
                message.setText("👤 Введите имя клиента:");
                message.setReplyMarkup(null);
                execute(message);
                userStates.put(chatIdStr, "ожидание имени");

            } else if (callbackData.equals("назначить звонок")) {
                SendMessage message = new SendMessage();
                message.setChatId(chatIdStr);
                message.setText("Введите номер телефона клиента для поиска:");
                message.setReplyMarkup(null);
                execute(message);
                userStates.put(chatIdStr, "поиск клиента для звонка");

            } else if (callbackData.equals("список клиентов")) {
                showClientsMenu(chatId);

            } else if (callbackData.startsWith("тип_")) {
                String propertyType = callbackData.replace("тип_", "");
                if (!tempDataMap.containsKey(chatId)) {
                    tempDataMap.put(chatId, new HashMap<>());
                }
                tempDataMap.get(chatId).put("type", propertyType);

                Map<String, String> data = tempDataMap.get(chatId);
                editMessage(chatId,
                        "👤 Имя клиента: " + data.get("name") + "\n" +
                                "📱 Телефон: " + data.get("phone") + "\n" +
                                "🏙️ Город: " + data.get("city") + "\n" +
                                "🏠 Тип недвижимости: " + propertyType + "\n\n" +
                                "Введите дату и время звонка (формат: ГГГГ-ММ-ДД ЧЧ:ММ):\nПример: 2025-12-25 14:30");
                userStates.put(chatIdStr, "ожидание даты звонка");

            } else if (callbackData.startsWith("назначить_")) {
                int clientId = Integer.parseInt(callbackData.replace("назначить_", ""));
                userStates.put(chatIdStr + "_meeting_client", String.valueOf(clientId));
                editMessage(chatId, "Введите дату и время звонка (формат: ГГГГ-ММ-ДД ЧЧ:ММ):\nПример: 2025-12-25 14:30");
                userStates.put(chatIdStr, "звонок дата");

            } else if (callbackData.startsWith("отложить_")) {
                String meetingKey = callbackData.replace("отложить_", "");
                userStates.put(chatIdStr + "_postpone", meetingKey);
                showPostponeMenu(chatId);

            } else if (callbackData.equals("отложить дни")) {
                editMessage(chatId, "Введите количество дней для переноса:");
                userStates.put(chatIdStr, "отложить дни");

            } else if (callbackData.equals("отложить часы")) {
                editMessage(chatId, "Введите количество часов для переноса:");
                userStates.put(chatIdStr, "отложить часы");

            } else if (callbackData.equals("отложить минуты")) {
                editMessage(chatId, "Введите количество минут для переноса:");
                userStates.put(chatIdStr, "отложить минуты");

            } else if (callbackData.startsWith("завершить_")) {
                String meetingKey = callbackData.replace("завершить_", "");
                showCompletionOptions(chatId, meetingKey);

            } else if (callbackData.startsWith("сделка_")) {
                String[] parts = callbackData.split("_");
                String meetingKey = parts[1];
                String result = parts[2];
                completeMeeting(chatId, meetingKey, result);

            } else if (callbackData.equals("ближайшие звонки")) {
                showUpcomingMeetings(chatId);

            } else if (callbackData.equals("весь список")) {
                sendAllClientsFile(chatId);

            } else if (callbackData.equals("список по типу")) {
                showPropertyTypesForList(chatId);

            } else if (callbackData.startsWith("фильтр_тип_")) {
                String propertyType = callbackData.replace("фильтр_тип_", "");
                sendClientsByTypeFile(chatId, propertyType);

            } else if (callbackData.equals("изменить данные")) {
                SendMessage message = new SendMessage();
                message.setChatId(chatIdStr);
                message.setText("Введите номер телефона клиента для изменения:");
                message.setReplyMarkup(null);
                execute(message);
                userStates.put(chatIdStr, "изменение телефона");

            } else if (callbackData.equals("удалить клиента")) {
                SendMessage message = new SendMessage();
                message.setChatId(chatIdStr);
                message.setText("Введите номер телефона клиента для удаления:");
                message.setReplyMarkup(null);
                execute(message);
                userStates.put(chatIdStr, "удаление телефона");

            } else if (callbackData.equals("статистика")) {
                showStatistics(chatId);

            } else if (callbackData.equals("список сделок")) {
                sendCompletedDealsFile(chatId, "совершена");

            } else if (callbackData.equals("список отказов")) {
                sendCompletedDealsFile(chatId, "отказ");

            } else if (callbackData.startsWith("изменить_")) {
                String param = callbackData.replace("изменить_", "");
                String clientIdStr = userStates.get(chatIdStr + "_edit_client");
                if (clientIdStr != null) {
                    int clientId = Integer.parseInt(clientIdStr);
                    userStates.put(chatIdStr + "_clientId", String.valueOf(clientId));

                    if (param.equals("property_type")) {
                        showPropertyTypeSelection(chatId, "edit_" + clientId);
                    } else {
                        String messageText = "Введите новое значение для ";
                        if (param.equals("name")) {
                            messageText += "имени:";
                            userStates.put(chatIdStr, "изменение имени");
                        } else if (param.equals("phone")) {
                            messageText += "телефона (формат: 81234567890):";
                            userStates.put(chatIdStr, "изменение телефона ввод");
                        } else if (param.equals("city")) {
                            messageText += "города:";
                            userStates.put(chatIdStr, "изменение города");
                        }
                        editMessage(chatId, messageText);
                    }
                }

            } else if (callbackData.startsWith("тип_изменить_")) {
                String[] parts = callbackData.split("_");
                String propertyType = parts[2];
                int clientId = Integer.parseInt(parts[3]);
                updateClientParameter(chatId, clientId, "property_type", propertyType);
                userStates.remove(chatIdStr + "_edit_client");
                userStates.remove(chatIdStr + "_clientId");

            } else if (callbackData.startsWith("удалить_")) {
                int clientId = Integer.parseInt(callbackData.replace("удалить_", ""));
                deleteClient(chatId, clientId);

            } else if (callbackData.equals("назад в меню")) {
                showMainMenu(chatId);

            } else if (callbackData.equals("назад к спискам")) {
                showClientsMenu(chatId);

            } else if (callbackData.equals("меню после операции")) {
                showMainMenu(chatId);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Показать приветственный экран
    private void showWelcomeScreen(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("🏢 ДОБРО ПОЖАЛОВАТЬ В БОТ ДЛЯ УПРАВЛЕНИЯ КЛИЕНТАМИ ПО НЕДВИЖИМОСТИ!\n\n" +
                "Этот бот поможет вам:\n\n" +
                "📌 Создавать и управлять клиентами\n" +
                "📅 Назначать и отслеживать звонки\n" +
                "📊 Вести статистику сделок\n" +
                "📋 Получать списки клиентов по разным критериям\n\n" +
                "Для начала работы выберите действие в меню ниже:");

        InlineKeyboardButton startBtn = InlineKeyboardButton.builder()
                .text("🚀 Начать работу")
                .callbackData("меню после операции")
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(startBtn))
                .build();

        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Редактировать существующее сообщение
    private void editMessage(Long chatId, String text) {
        try {
            Integer messageId = lastMessageIdMap.get(chatId);
            if (messageId != null) {
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText(text);
                message.setReplyMarkup(null);

                // Сначала отправляем новое сообщение
                execute(message);

                // Сохраняем ID нового сообщения
                // Note: В реальности нужно получить ID отправленного сообщения,
                // но для упрощения будем обновлять lastMessageIdMap при callback
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Показать выбор типа недвижимости
    private void showPropertyTypeSelection(Long chatId, String context) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Выберите тип недвижимости:");

        InlineKeyboardMarkup keyboard;
        if (context.startsWith("edit_")) {
            int clientId = Integer.parseInt(context.replace("edit_", ""));
            keyboard = getPropertyTypeKeyboardForEdit(clientId);
        } else {
            keyboard = getPropertyTypeKeyboardForCreate();
        }

        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Клавиатура для выбора типа недвижимости при создании
    private InlineKeyboardMarkup getPropertyTypeKeyboardForCreate() {
        InlineKeyboardButton studioBtn = InlineKeyboardButton.builder()
                .text("🏢 Студия")
                .callbackData("тип_студия")
                .build();

        InlineKeyboardButton oneRoomBtn = InlineKeyboardButton.builder()
                .text("1️⃣ 1-комнатная")
                .callbackData("тип_1-комнатная")
                .build();

        InlineKeyboardButton twoRoomBtn = InlineKeyboardButton.builder()
                .text("2️⃣ 2-комнатная")
                .callbackData("тип_2-комнатная")
                .build();

        InlineKeyboardButton threeRoomBtn = InlineKeyboardButton.builder()
                .text("3️⃣ 3-комнатная")
                .callbackData("тип_3-комнатная")
                .build();

        InlineKeyboardButton houseBtn = InlineKeyboardButton.builder()
                .text("🏡 Дом")
                .callbackData("тип_дом")
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(studioBtn, oneRoomBtn))
                .keyboardRow(List.of(twoRoomBtn, threeRoomBtn))
                .keyboardRow(List.of(houseBtn))
                .build();
    }

    // Клавиатура для выбора типа недвижимости при редактировании
    private InlineKeyboardMarkup getPropertyTypeKeyboardForEdit(int clientId) {
        InlineKeyboardButton studioBtn = InlineKeyboardButton.builder()
                .text("🏢 Студия")
                .callbackData("тип_изменить_студия_" + clientId)
                .build();

        InlineKeyboardButton oneRoomBtn = InlineKeyboardButton.builder()
                .text("1️⃣ 1-комнатная")
                .callbackData("тип_изменить_1-комнатная_" + clientId)
                .build();

        InlineKeyboardButton twoRoomBtn = InlineKeyboardButton.builder()
                .text("2️⃣ 2-комнатная")
                .callbackData("тип_изменить_2-комнатная_" + clientId)
                .build();

        InlineKeyboardButton threeRoomBtn = InlineKeyboardButton.builder()
                .text("3️⃣ 3-комнатная")
                .callbackData("тип_изменить_3-комнатная_" + clientId)
                .build();

        InlineKeyboardButton houseBtn = InlineKeyboardButton.builder()
                .text("🏡 Дом")
                .callbackData("тип_изменить_дом_" + clientId)
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(studioBtn, oneRoomBtn))
                .keyboardRow(List.of(twoRoomBtn, threeRoomBtn))
                .keyboardRow(List.of(houseBtn))
                .build();
    }

    // Клавиатура для возврата в меню
    private InlineKeyboardMarkup getBackToMenuKeyboard() {
        InlineKeyboardButton menuBtn = InlineKeyboardButton.builder()
                .text("🏠 Главное меню")
                .callbackData("меню после операции")
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(menuBtn))
                .build();
    }

    // Показать главное меню
    private void showMainMenu(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("🏢 УПРАВЛЕНИЕ КЛИЕНТАМИ ПО НЕДВИЖИМОСТИ\n\n" +
                "Выберите действие:");

        InlineKeyboardButton btn1 = InlineKeyboardButton.builder()
                .text("➕ Добавить клиента")
                .callbackData("добавить клиента")
                .build();

        InlineKeyboardButton btn2 = InlineKeyboardButton.builder()
                .text("📅 Назначить звонок клиенту")
                .callbackData("назначить звонок")
                .build();

        InlineKeyboardButton btn3 = InlineKeyboardButton.builder()
                .text("📋 Списки клиентов")
                .callbackData("список клиентов")
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(btn1))
                .keyboardRow(List.of(btn2))
                .keyboardRow(List.of(btn3))
                .build();

        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Показать меню работы с клиентами
    private void showClientsMenu(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("📋 РАБОТА С КЛИЕНТАМИ\n\nВыберите действие:");

        InlineKeyboardButton btn1 = InlineKeyboardButton.builder()
                .text("📅 Ближайшие звонки")
                .callbackData("ближайшие звонки")
                .build();

        InlineKeyboardButton btn2 = InlineKeyboardButton.builder()
                .text("📄 Весь список клиентов")
                .callbackData("весь список")
                .build();

        InlineKeyboardButton btn3 = InlineKeyboardButton.builder()
                .text("🏠 Список по типу недвижимости")
                .callbackData("список по типу")
                .build();

        InlineKeyboardButton btn4 = InlineKeyboardButton.builder()
                .text("✏️ Изменить данные клиента")
                .callbackData("изменить данные")
                .build();

        InlineKeyboardButton btn5 = InlineKeyboardButton.builder()
                .text("🗑️ Удалить клиента")
                .callbackData("удалить клиента")
                .build();

        InlineKeyboardButton btn6 = InlineKeyboardButton.builder()
                .text("📊 Статистика")
                .callbackData("статистика")
                .build();

        InlineKeyboardButton btnBack = InlineKeyboardButton.builder()
                .text("🔙 Назад в меню")
                .callbackData("назад в меню")
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(btn1))
                .keyboardRow(List.of(btn2, btn3))
                .keyboardRow(List.of(btn4, btn5))
                .keyboardRow(List.of(btn6))
                .keyboardRow(List.of(btnBack))
                .build();

        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Инициализация подключения к базе данных
    private void initializeDatabase() {
        try {
            if (connection == null || connection.isClosed()) {
                Class.forName("com.mysql.cj.jdbc.Driver");
                connection = DriverManager.getConnection(
                        "jdbc:mysql://localhost:3306/investment_tg_bot",
                        "root",
                        "010203456456"
                );
                createTables();
                System.out.println("База данных подключена успешно");
            }
        } catch (Exception e) {
            System.out.println("Ошибка подключения к БД: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Создание таблиц в базе данных
    private void createTables() throws SQLException {
        String createClientsTable = """
    CREATE TABLE IF NOT EXISTS clients (
        id INT AUTO_INCREMENT PRIMARY KEY,
        name VARCHAR(100) NOT NULL,
        phone VARCHAR(20) NOT NULL UNIQUE,
        city VARCHAR(100) NOT NULL,
        property_type VARCHAR(50) NOT NULL,
        meeting_time DATETIME,
        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
        INDEX idx_phone (phone),
        INDEX idx_meeting_time (meeting_time)
    )
    """;

        String createDealsTable = """
    CREATE TABLE IF NOT EXISTS deals (
        id INT AUTO_INCREMENT PRIMARY KEY,
        client_id INT,
        name VARCHAR(100) NOT NULL,
        phone VARCHAR(20) NOT NULL,
        city VARCHAR(100) NOT NULL,
        property_type VARCHAR(50) NOT NULL,
        result VARCHAR(50) NOT NULL,
        deal_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    )
    """;

        try (Statement stmt = connection.createStatement()) {
            // Создаем таблицу clients
            stmt.execute(createClientsTable);

            // Создаем таблицу deals (она создастся только если не существует)
            stmt.execute(createDealsTable);

            System.out.println("Таблицы созданы/проверены успешно");

        } catch (SQLException e) {
            System.out.println("Ошибка при создании таблиц: " + e.getMessage());
            throw e;
        }
    }

    // Сохранение клиента в базу данных
    private void saveClientToDatabase(String name, String phone, String city, String propertyType, String meetingTime) {
        initializeDatabase();

        String sql = "INSERT INTO clients (name, phone, city, property_type, meeting_time) VALUES (?, ?, ?, ?, ?)";

        try (PreparedStatement pstmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            pstmt.setString(1, name);
            pstmt.setString(2, phone);
            pstmt.setString(3, city);
            pstmt.setString(4, propertyType);
            pstmt.setString(5, meetingTime + ":00");
            pstmt.executeUpdate();
            System.out.println("Клиент сохранен: " + name + ", телефон: " + phone);

        } catch (SQLException e) {
            System.out.println("Ошибка сохранения клиента: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Планирование уведомлений о звонке
    private void scheduleMeetingNotifications(Long chatId, String name, String phone, String meetingTimeStr) {
        try {
            LocalDateTime meetingTime = LocalDateTime.parse(meetingTimeStr + ":00", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            // Напоминание за 5 минут
            LocalDateTime notification5min = meetingTime.minusMinutes(5);
            if (notification5min.isAfter(LocalDateTime.now())) {
                long delay5min = Duration.between(LocalDateTime.now(), notification5min).getSeconds();
                scheduler.schedule(() -> {
                    sendSimpleReminder(chatId, name, phone, meetingTimeStr);
                }, delay5min, TimeUnit.SECONDS);
                System.out.println("Напоминание за 5 минут запланировано на: " + notification5min);
            }

            // Уведомление о начале звонка
            if (meetingTime.isAfter(LocalDateTime.now())) {
                long delayExact = Duration.between(LocalDateTime.now(), meetingTime).getSeconds();
                scheduler.schedule(() -> {
                    sendMeetingNotificationWithMenu(chatId, name, phone, meetingTimeStr);
                }, delayExact, TimeUnit.SECONDS);
                System.out.println("Уведомление о начале звонка запланировано на: " + meetingTime);
            } else {
                sendMeetingNotificationWithMenu(chatId, name, phone, meetingTimeStr);
            }

        } catch (Exception e) {
            System.out.println("Ошибка планирования уведомлений: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Отправка простого напоминания
    private void sendSimpleReminder(Long chatId, String name, String phone, String meetingTime) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("⏰ Напоминание: звонок с клиентом " + name + " через 5 минут!\n" +
                "📅 Время: " + meetingTime + "\n" +
                "📱 Телефон: " + phone);

        try {
            execute(message);
            System.out.println("Простое напоминание отправлено для клиента: " + name);
        } catch (Exception e) {
            System.out.println("Ошибка отправки простого напоминания: " + e.getMessage());
        }
    }

    // Отправка уведомления о звонке с меню действий
    private void sendMeetingNotificationWithMenu(Long chatId, String name, String phone, String meetingTime) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("🕐 ВРЕМЯ ЗВОНКА!\n\n" +
                "👤 Клиент: " + name + "\n" +
                "📱 Телефон: " + phone + "\n" +
                "📅 Время звонка: " + meetingTime + "\n\n" +
                "Выберите действие:");

        String meetingKey = phone + "_" + System.currentTimeMillis(); // Уникальный ключ встречи

        InlineKeyboardButton postponeBtn = InlineKeyboardButton.builder()
                .text("📅 Отложить звонок")
                .callbackData("отложить_" + meetingKey)
                .build();

        InlineKeyboardButton completeBtn = InlineKeyboardButton.builder()
                .text("✅ Сделка завершена")
                .callbackData("завершить_" + meetingKey)
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(postponeBtn))
                .keyboardRow(List.of(completeBtn))
                .build();

        message.setReplyMarkup(keyboard);

        try {
            execute(message);
            System.out.println("Уведомление с меню отправлено для клиента: " + name);
        } catch (Exception e) {
            System.out.println("Ошибка отправки уведомления с меню: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Показать меню откладывания звонка
    private void showPostponeMenu(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Выберите, на сколько отложить звонок:");

        InlineKeyboardButton daysBtn = InlineKeyboardButton.builder()
                .text("📅 Дни")
                .callbackData("отложить дни")
                .build();

        InlineKeyboardButton hoursBtn = InlineKeyboardButton.builder()
                .text("⏰ Часы")
                .callbackData("отложить часы")
                .build();

        InlineKeyboardButton minutesBtn = InlineKeyboardButton.builder()
                .text("⏱️ Минуты")
                .callbackData("отложить минуты")
                .build();

        InlineKeyboardButton backBtn = InlineKeyboardButton.builder()
                .text("🔙 Отмена")
                .callbackData("назад в меню")
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(daysBtn, hoursBtn, minutesBtn))
                .keyboardRow(List.of(backBtn))
                .build();

        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Отложить звонок
    private void postponeMeeting(Long chatId, String meetingKey, int days, int hours, int minutes) {
        try {
            System.out.println("Откладывание звонка, meetingKey: " + meetingKey);

            String phone;
            if (meetingKey.contains("_")) {
                String[] parts = meetingKey.split("_");
                if (parts.length >= 1) {
                    phone = parts[0];
                } else {
                    SendMessage message = new SendMessage();
                    message.setChatId(chatId.toString());
                    message.setText("❌ Неверный формат данных звонка.");
                    execute(message);
                    return;
                }
            } else {
                phone = meetingKey;
            }

            System.out.println("Откладывание звонка для телефона: " + phone);

            initializeDatabase();

            String findSql = "SELECT id, name, meeting_time FROM clients WHERE phone = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(findSql)) {
                pstmt.setString(1, phone);
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    int clientId = rs.getInt("id");
                    String name = rs.getString("name");
                    Timestamp oldTimestamp = rs.getTimestamp("meeting_time");

                    if (oldTimestamp == null) {
                        SendMessage message = new SendMessage();
                        message.setChatId(chatId.toString());
                        message.setText("❌ У клиента нет назначенного звонка.");
                        execute(message);
                        return;
                    }

                    LocalDateTime newTime = oldTimestamp.toLocalDateTime()
                            .plusDays(days)
                            .plusHours(hours)
                            .plusMinutes(minutes);

                    String updateSql = "UPDATE clients SET meeting_time = ? WHERE id = ?";
                    try (PreparedStatement updateStmt = connection.prepareStatement(updateSql)) {
                        updateStmt.setTimestamp(1, Timestamp.valueOf(newTime));
                        updateStmt.setInt(2, clientId);
                        updateStmt.executeUpdate();
                        System.out.println("Время звонка обновлено в clients");
                    }

                    // Отмена старых задач уведомлений
                    for (String key : new ArrayList<>(meetingTasks.keySet())) {
                        if (key.contains("_" + phone + "_")) {
                            ScheduledFuture<?> task = meetingTasks.get(key);
                            if (task != null) {
                                task.cancel(false);
                            }
                            meetingTasks.remove(key);
                        }
                    }

                    // Планирование новых уведомлений
                    String newTimeStr = newTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                    scheduleMeetingNotifications(chatId, name, phone, newTimeStr);

                    SendMessage message = new SendMessage();
                    message.setChatId(chatId.toString());
                    message.setText("✅ Звонок отложен на:\n" +
                            (days > 0 ? "📅 Дней: " + days + "\n" : "") +
                            (hours > 0 ? "⏰ Часов: " + hours + "\n" : "") +
                            (minutes > 0 ? "⏱️ Минут: " + minutes + "\n" : "") +
                            "\nНовое время звонка: " + newTimeStr);
                    message.setReplyMarkup(getBackToMenuKeyboard());
                    execute(message);

                    System.out.println("Звонок успешно отложен на новое время: " + newTimeStr);
                } else {
                    SendMessage message = new SendMessage();
                    message.setChatId(chatId.toString());
                    message.setText("❌ Клиент с телефоном " + phone + " не найден в базе данных.");
                    message.setReplyMarkup(getBackToMenuKeyboard());
                    execute(message);
                    System.out.println("Клиент не найден по телефону: " + phone);
                }
            }
        } catch (Exception e) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText("❌ Ошибка при откладывании звонка: " + e.getMessage());
            message.setReplyMarkup(getBackToMenuKeyboard());
            try {
                execute(message);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            System.out.println("Ошибка при откладывании звонка: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Показать опции завершения сделки
    private void showCompletionOptions(Long chatId, String meetingKey) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Выберите результат сделки:");

        String simplifiedKey;
        if (meetingKey.contains("_")) {
            String[] parts = meetingKey.split("_");
            simplifiedKey = parts[0];
        } else {
            simplifiedKey = meetingKey;
        }

        InlineKeyboardButton successBtn = InlineKeyboardButton.builder()
                .text("✅ Сделка совершилась")
                .callbackData("сделка_" + simplifiedKey + "_совершена")
                .build();

        InlineKeyboardButton refuseBtn = InlineKeyboardButton.builder()
                .text("❌ Сделка отказана")
                .callbackData("сделка_" + simplifiedKey + "_отказ")
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(successBtn))
                .keyboardRow(List.of(refuseBtn))
                .build();

        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Завершение звонка и сохранение сделки
    private void completeMeeting(Long chatId, String meetingKey, String result) {
        try {
            System.out.println("Завершение сделки, meetingKey: " + meetingKey + ", result: " + result);

            String phone;

            if (meetingKey.contains("_")) {
                String[] parts = meetingKey.split("_");
                if (parts.length >= 1) {
                    phone = parts[0];
                } else {
                    SendMessage message = new SendMessage();
                    message.setChatId(chatId.toString());
                    message.setText("❌ Неверный формат данных звонка.");
                    message.setReplyMarkup(getBackToMenuKeyboard());
                    execute(message);
                    return;
                }
            } else {
                phone = meetingKey;
            }

            System.out.println("Завершение сделки для телефона: " + phone + ", результат: " + result);

            initializeDatabase();

            String findSql = "SELECT * FROM clients WHERE phone = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(findSql)) {
                pstmt.setString(1, phone);
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    int clientId = rs.getInt("id");
                    String name = rs.getString("name");
                    String city = rs.getString("city");
                    String propertyType = rs.getString("property_type");

                    // Всегда добавляем новую запись в deals, даже если клиент уже есть
                    String dealSql = "INSERT INTO deals (client_id, name, phone, city, property_type, result, deal_date) VALUES (?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement dealStmt = connection.prepareStatement(dealSql)) {
                        dealStmt.setInt(1, clientId);
                        dealStmt.setString(2, name);
                        dealStmt.setString(3, phone);
                        dealStmt.setString(4, city);
                        dealStmt.setString(5, propertyType);
                        dealStmt.setString(6, result);
                        dealStmt.setTimestamp(7, Timestamp.valueOf(LocalDateTime.now()));
                        dealStmt.executeUpdate();
                        System.out.println("Новая сделка сохранена в deals: " + result + " для клиента " + name + " (ID: " + clientId + ")");
                    }

                    // Отмена запланированных уведомлений
                    for (String key : new ArrayList<>(meetingTasks.keySet())) {
                        if (key.contains("_" + phone + "_")) {
                            ScheduledFuture<?> task = meetingTasks.get(key);
                            if (task != null) {
                                task.cancel(false);
                            }
                            meetingTasks.remove(key);
                        }
                    }

                    SendMessage message = new SendMessage();
                    message.setChatId(chatId.toString());
                    message.setText("✅ Сделка завершена!\n" +
                            "👤 Клиент: " + name + "\n" +
                            "📱 Телефон: " + phone + "\n" +
                            "📊 Результат: " + (result.equals("совершена") ? "✅ Совершена" : "❌ Отказ"));
                    message.setReplyMarkup(getBackToMenuKeyboard());
                    execute(message);

                    System.out.println("Сделка успешно завершена для клиента: " + name);
                } else {
                    SendMessage message = new SendMessage();
                    message.setChatId(chatId.toString());
                    message.setText("❌ Клиент с телефоном " + phone + " не найден.");
                    message.setReplyMarkup(getBackToMenuKeyboard());
                    execute(message);
                    System.out.println("Клиент не найден при завершении сделки: " + phone);
                }
            }
        } catch (Exception e) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText("❌ Ошибка при завершении сделки: " + e.getMessage());
            message.setReplyMarkup(getBackToMenuKeyboard());
            try {
                execute(message);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            System.out.println("Ошибка при завершении сделки: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Поиск клиента для назначения звонка
    private void searchClientForMeeting(Long chatId, String phone) {
        System.out.println("Поиск клиента для звонка по телефону: " + phone);
        initializeDatabase();

        String sql = "SELECT id, name, city, property_type FROM clients WHERE phone = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, phone);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                int clientId = rs.getInt("id");
                String name = rs.getString("name");
                String city = rs.getString("city");
                String propertyType = rs.getString("property_type");

                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("✅ Клиент найден!\n\n" +
                        "👤 Имя: " + name + "\n" +
                        "📱 Телефон: " + phone + "\n" +
                        "🏙️ Город: " + city + "\n" +
                        "🏠 Тип недвижимости: " + propertyType + "\n\n" +
                        "Назначить звонок?");

                InlineKeyboardButton appointBtn = InlineKeyboardButton.builder()
                        .text("📅 Назначить звонок")
                        .callbackData("назначить_" + clientId)
                        .build();

                InlineKeyboardButton cancelBtn = InlineKeyboardButton.builder()
                        .text("❌ Отмена")
                        .callbackData("назад в меню")
                        .build();

                InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                        .keyboardRow(List.of(appointBtn))
                        .keyboardRow(List.of(cancelBtn))
                        .build();

                message.setReplyMarkup(keyboard);
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    throw new RuntimeException(e);
                }
                System.out.println("Клиент найден: " + name);

            } else {
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("❌ Клиент с телефоном " + phone + " не найден.\n" +
                        "Попробуйте еще раз или создайте нового клиента.");
                message.setReplyMarkup(getBackToMenuKeyboard());
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    throw new RuntimeException(e);
                }
                userStates.remove(chatId.toString());
                System.out.println("Клиент не найден: " + phone);
            }
        } catch (SQLException e) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText("❌ Ошибка поиска клиента: " + e.getMessage());
            message.setReplyMarkup(getBackToMenuKeyboard());
            try {
                execute(message);
            } catch (TelegramApiException ex) {
                throw new RuntimeException(ex);
            }
            System.out.println("Ошибка поиска клиента: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Обновление времени звонка
    private void updateMeetingTime(Long chatId, int clientId, String meetingTime) {
        try {
            System.out.println("Обновление времени звонка для клиента ID: " + clientId + " на время: " + meetingTime);
            initializeDatabase();

            String clientSql = "SELECT name, phone FROM clients WHERE id = ?";
            String name = "";
            String phone = "";

            try (PreparedStatement pstmt = connection.prepareStatement(clientSql)) {
                pstmt.setInt(1, clientId);
                ResultSet rs = pstmt.executeQuery();

                if (rs.next()) {
                    name = rs.getString("name");
                    phone = rs.getString("phone");
                    System.out.println("Найден клиент: " + name + ", телефон: " + phone);
                }
            }

            if (name.isEmpty()) {
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("❌ Клиент не найден.");
                message.setReplyMarkup(getBackToMenuKeyboard());
                execute(message);
                return;
            }

            String updateSql = "UPDATE clients SET meeting_time = ? WHERE id = ?";
            try (PreparedStatement pstmt = connection.prepareStatement(updateSql)) {
                pstmt.setString(1, meetingTime + ":00");
                pstmt.setInt(2, clientId);
                pstmt.executeUpdate();
                System.out.println("Время звонка обновлено в clients");
            }

            scheduleMeetingNotifications(chatId, name, phone, meetingTime);

            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText("✅ Звонок назначен!\n\n" +
                    "👤 Клиент: " + name + "\n" +
                    "📱 Телефон: " + phone + "\n" +
                    "📅 Звонок: " + meetingTime + "\n\n" +
                    "Вы получите уведомления о звонке.");
            message.setReplyMarkup(getBackToMenuKeyboard());
            execute(message);

            System.out.println("Звонок успешно назначен для клиента: " + name);

        } catch (Exception e) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText("❌ Ошибка при назначении звонка: " + e.getMessage());
            message.setReplyMarkup(getBackToMenuKeyboard());
            try {
                execute(message);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            System.out.println("Ошибка при назначении звонка: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Показать ближайшие звонки
    private void showUpcomingMeetings(Long chatId) {
        initializeDatabase();

        String sql = "SELECT name, phone, city, property_type, meeting_time " +
                "FROM clients " +
                "WHERE meeting_time IS NOT NULL AND meeting_time > NOW() " +
                "ORDER BY meeting_time ASC " +
                "LIMIT 5";

        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery(sql);

            StringBuilder response = new StringBuilder();
            response.append("📅 БЛИЖАЙШИЕ ЗВОНКИ:\n\n");

            int count = 1;
            while (rs.next()) {
                response.append(count).append(". 👤 ").append(rs.getString("name"))
                        .append("\n   📱 ").append(rs.getString("phone"))
                        .append("\n   🏙️ ").append(rs.getString("city"))
                        .append("\n   🏠 ").append(rs.getString("property_type"))
                        .append("\n   📅 ").append(rs.getTimestamp("meeting_time").toLocalDateTime().format(formatter))
                        .append("\n   ──────────────\n");
                count++;
            }

            if (count == 1) {
                response.append("❌ Нет запланированных звонков");
            }

            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText(response.toString());

            InlineKeyboardButton backBtn = InlineKeyboardButton.builder()
                    .text("🔙 Назад")
                    .callbackData("назад к спискам")
                    .build();

            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(List.of(backBtn))
                    .build();

            message.setReplyMarkup(keyboard);
            execute(message);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Отправить файл со всеми клиентами
    private void sendAllClientsFile(Long chatId) {
        initializeDatabase();

        try {
            File file = new File("all_clients.txt");
            FileWriter writer = new FileWriter(file);

            String sql = "SELECT name, phone, city, property_type, meeting_time, created_at FROM clients ORDER BY created_at DESC";

            try (Statement stmt = connection.createStatement()) {
                ResultSet rs = stmt.executeQuery(sql);

                writer.write("СПИСОК ВСЕХ КЛИЕНТОВ\n");
                writer.write("=".repeat(50) + "\n\n");

                int count = 1;
                while (rs.next()) {
                    writer.write(count + ". Имя: " + rs.getString("name") + "\n");
                    writer.write("   Телефон: " + rs.getString("phone") + "\n");
                    writer.write("   Город: " + rs.getString("city") + "\n");
                    writer.write("   Тип недвижимости: " + rs.getString("property_type") + "\n");

                    Timestamp meetingTime = rs.getTimestamp("meeting_time");
                    if (meetingTime != null) {
                        writer.write("   Звонок: " + meetingTime.toLocalDateTime().format(formatter) + "\n");
                    }

                    writer.write("   Создан: " + rs.getTimestamp("created_at").toLocalDateTime().format(formatter) + "\n");
                    writer.write("-".repeat(30) + "\n");
                    count++;
                }

                writer.write("\nВсего клиентов: " + (count - 1));
            }

            writer.close();

            SendDocument document = new SendDocument();
            document.setChatId(chatId.toString());
            document.setDocument(new InputFile(file));
            document.setCaption("📄 Список всех клиентов");

            execute(document);

            file.delete();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Показать типы недвижимости для фильтрации
    private void showPropertyTypesForList(Long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText("Выберите тип недвижимости для фильтрации:");

        InlineKeyboardButton studioBtn = InlineKeyboardButton.builder()
                .text("🏢 Студия")
                .callbackData("фильтр_тип_студия")
                .build();

        InlineKeyboardButton oneRoomBtn = InlineKeyboardButton.builder()
                .text("1️⃣ 1-комнатная")
                .callbackData("фильтр_тип_1-комнатная")
                .build();

        InlineKeyboardButton twoRoomBtn = InlineKeyboardButton.builder()
                .text("2️⃣ 2-комнатная")
                .callbackData("фильтр_тип_2-комнатная")
                .build();

        InlineKeyboardButton threeRoomBtn = InlineKeyboardButton.builder()
                .text("3️⃣ 3-комнатная")
                .callbackData("фильтр_тип_3-комнатная")
                .build();

        InlineKeyboardButton houseBtn = InlineKeyboardButton.builder()
                .text("🏡 Дом")
                .callbackData("фильтр_тип_дом")
                .build();

        InlineKeyboardButton backBtn = InlineKeyboardButton.builder()
                .text("🔙 Назад")
                .callbackData("назад к спискам")
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(studioBtn, oneRoomBtn))
                .keyboardRow(List.of(twoRoomBtn, threeRoomBtn))
                .keyboardRow(List.of(houseBtn))
                .keyboardRow(List.of(backBtn))
                .build();

        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Отправить файл с клиентами по типу недвижимости
    private void sendClientsByTypeFile(Long chatId, String propertyType) {
        initializeDatabase();

        try {
            File file = new File("clients_" + propertyType + ".txt");
            FileWriter writer = new FileWriter(file);

            String sql = "SELECT name, phone, city, meeting_time, created_at FROM clients WHERE property_type = ? ORDER BY created_at DESC";

            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, propertyType);
                ResultSet rs = pstmt.executeQuery();

                writer.write("КЛИЕНТЫ ПО ТИПУ НЕДВИЖИМОСТИ: " + propertyType.toUpperCase() + "\n");
                writer.write("=".repeat(50) + "\n\n");

                int count = 1;
                while (rs.next()) {
                    writer.write(count + ". Имя: " + rs.getString("name") + "\n");
                    writer.write("   Телефон: " + rs.getString("phone") + "\n");
                    writer.write("   Город: " + rs.getString("city") + "\n");

                    Timestamp meetingTime = rs.getTimestamp("meeting_time");
                    if (meetingTime != null) {
                        writer.write("   Звонок: " + meetingTime.toLocalDateTime().format(formatter) + "\n");
                    }

                    writer.write("   Создан: " + rs.getTimestamp("created_at").toLocalDateTime().format(formatter) + "\n");
                    writer.write("-".repeat(30) + "\n");
                    count++;
                }

                writer.write("\nВсего клиентов: " + (count - 1));
            }

            writer.close();

            SendDocument document = new SendDocument();
            document.setChatId(chatId.toString());
            document.setDocument(new InputFile(file));
            document.setCaption("🏠 Клиенты с типом недвижимости: " + propertyType);

            execute(document);

            file.delete();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Поиск клиента для редактирования
    private void searchClientForEdit(Long chatId, String phone) {
        System.out.println("Поиск клиента для редактирования по телефону: " + phone);
        initializeDatabase();

        String sql = "SELECT id, name, city, property_type FROM clients WHERE phone = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, phone);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                int clientId = rs.getInt("id");
                String name = rs.getString("name");

                userStates.put(chatId.toString() + "_edit_client", String.valueOf(clientId));

                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("✅ Клиент найден!\n\n" +
                        "👤 Имя: " + name + "\n" +
                        "📱 Телефон: " + phone + "\n" +
                        "🏙️ Город: " + rs.getString("city") + "\n" +
                        "🏠 Тип недвижимости: " + rs.getString("property_type") + "\n\n" +
                        "Выберите параметр для изменения:");

                InlineKeyboardButton nameBtn = InlineKeyboardButton.builder()
                        .text("👤 Имя")
                        .callbackData("изменить_name")
                        .build();

                InlineKeyboardButton phoneBtn = InlineKeyboardButton.builder()
                        .text("📱 Телефон")
                        .callbackData("изменить_phone")
                        .build();

                InlineKeyboardButton cityBtn = InlineKeyboardButton.builder()
                        .text("🏙️ Город")
                        .callbackData("изменить_city")
                        .build();

                InlineKeyboardButton typeBtn = InlineKeyboardButton.builder()
                        .text("🏠 Тип недвижимости")
                        .callbackData("изменить_property_type")
                        .build();

                InlineKeyboardButton cancelBtn = InlineKeyboardButton.builder()
                        .text("❌ Отмена")
                        .callbackData("назад к спискам")
                        .build();

                InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                        .keyboardRow(List.of(nameBtn))
                        .keyboardRow(List.of(phoneBtn))
                        .keyboardRow(List.of(cityBtn))
                        .keyboardRow(List.of(typeBtn))
                        .keyboardRow(List.of(cancelBtn))
                        .build();

                message.setReplyMarkup(keyboard);
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    throw new RuntimeException(e);
                }
                System.out.println("Клиент найден для редактирования: " + name);

            } else {
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("❌ Клиент с телефоном " + phone + " не найден.");
                message.setReplyMarkup(getBackToMenuKeyboard());
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    throw new RuntimeException(e);
                }
                userStates.remove(chatId.toString());
                System.out.println("Клиент не найден для редактирования: " + phone);
            }
        } catch (SQLException e) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText("❌ Ошибка поиска клиента: " + e.getMessage());
            message.setReplyMarkup(getBackToMenuKeyboard());
            try {
                execute(message);
            } catch (TelegramApiException ex) {
                throw new RuntimeException(ex);
            }
            System.out.println("Ошибка поиска клиента для редактирования: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Обновление параметра клиента
    private void updateClientParameter(Long chatId, int clientId, String param, String newValue) {
        initializeDatabase();

        String sql = "UPDATE clients SET " + param + " = ? WHERE id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, newValue);
            pstmt.setInt(2, clientId);
            int rows = pstmt.executeUpdate();

            if (rows > 0) {
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("✅ Параметр успешно изменен!\n" +
                        getParamName(param) + " установлено в: " + newValue);
                message.setReplyMarkup(getBackToMenuKeyboard());
                execute(message);
                System.out.println("Параметр " + param + " обновлен для клиента ID: " + clientId);
            } else {
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("❌ Ошибка при изменении параметра");
                message.setReplyMarkup(getBackToMenuKeyboard());
                execute(message);
            }

        } catch (Exception e) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText("❌ Ошибка при обновлении данных: " + e.getMessage());
            message.setReplyMarkup(getBackToMenuKeyboard());
            try {
                execute(message);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            System.out.println("Ошибка при обновлении параметра: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Получить человекочитаемое имя параметра
    private String getParamName(String param) {
        switch (param) {
            case "name": return "Имя";
            case "phone": return "Телефон";
            case "city": return "Город";
            case "property_type": return "Тип недвижимости";
            default: return param;
        }
    }

    // Поиск клиента для удаления
    private void searchClientForDelete(Long chatId, String phone) {
        System.out.println("Поиск клиента для удаления по телефону: " + phone);
        initializeDatabase();

        String sql = "SELECT id, name, city, property_type FROM clients WHERE phone = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, phone);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                int clientId = rs.getInt("id");
                String name = rs.getString("name");

                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("⚠️ ВЫ УВЕРЕНЫ, ЧТО ХОТИТЕ УДАЛИТЬ КЛИЕНТА?\n\n" +
                        "👤 Имя: " + name + "\n" +
                        "📱 Телефон: " + phone + "\n" +
                        "🏙️ Город: " + rs.getString("city") + "\n" +
                        "🏠 Тип недвижимости: " + rs.getString("property_type"));

                InlineKeyboardButton deleteBtn = InlineKeyboardButton.builder()
                        .text("✅ Да, удалить")
                        .callbackData("удалить_" + clientId)
                        .build();

                InlineKeyboardButton cancelBtn = InlineKeyboardButton.builder()
                        .text("❌ Нет, отмена")
                        .callbackData("назад к спискам")
                        .build();

                InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                        .keyboardRow(List.of(deleteBtn))
                        .keyboardRow(List.of(cancelBtn))
                        .build();

                message.setReplyMarkup(keyboard);
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    throw new RuntimeException(e);
                }
                System.out.println("Клиент найден для удаления: " + name);

            } else {
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("❌ Клиент с телефоном " + phone + " не найден.");
                message.setReplyMarkup(getBackToMenuKeyboard());
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    throw new RuntimeException(e);
                }
                userStates.remove(chatId.toString());
                System.out.println("Клиент не найден для удаления: " + phone);
            }
        } catch (SQLException e) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText("❌ Ошибка поиска клиента: " + e.getMessage());
            message.setReplyMarkup(getBackToMenuKeyboard());
            try {
                execute(message);
            } catch (TelegramApiException ex) {
                throw new RuntimeException(ex);
            }
            System.out.println("Ошибка поиска клиента для удаления: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Удаление клиента
    private void deleteClient(Long chatId, int clientId) {
        initializeDatabase();

        String sql = "DELETE FROM clients WHERE id = ?";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setInt(1, clientId);
            int rows = pstmt.executeUpdate();

            if (rows > 0) {
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("✅ Клиент успешно удален!");
                message.setReplyMarkup(getBackToMenuKeyboard());
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    throw new RuntimeException(e);
                }
                System.out.println("Клиент ID: " + clientId + " удален");
            } else {
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText("❌ Клиент не найден для удаления");
                message.setReplyMarkup(getBackToMenuKeyboard());
                try {
                    execute(message);
                } catch (TelegramApiException e) {
                    throw new RuntimeException(e);
                }
            }

            userStates.remove(chatId.toString());

        } catch (SQLException e) {
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText("❌ Ошибка при удалении клиента: " + e.getMessage());
            message.setReplyMarkup(getBackToMenuKeyboard());
            try {
                execute(message);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            System.out.println("Ошибка при удалении клиента: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Показать статистику
    private void showStatistics(Long chatId) {
        initializeDatabase();

        try {
            String totalSql = "SELECT COUNT(*) as total FROM clients";
            int totalClients = 0;
            try (Statement stmt = connection.createStatement()) {
                ResultSet rs = stmt.executeQuery(totalSql);
                if (rs.next()) {
                    totalClients = rs.getInt("total");
                }
            }

            String successSql = "SELECT COUNT(*) as success FROM deals WHERE result = 'совершена'";
            int successDeals = 0;
            try (Statement stmt = connection.createStatement()) {
                ResultSet rs = stmt.executeQuery(successSql);
                if (rs.next()) {
                    successDeals = rs.getInt("success");
                }
            }

            String refuseSql = "SELECT COUNT(*) as refuse FROM deals WHERE result = 'отказ'";
            int refuseDeals = 0;
            try (Statement stmt = connection.createStatement()) {
                ResultSet rs = stmt.executeQuery(refuseSql);
                if (rs.next()) {
                    refuseDeals = rs.getInt("refuse");
                }
            }

            // Расчет конверсии на основе количества сделок
            int totalDeals = successDeals + refuseDeals;
            double conversionRate = 0.0;

            if (totalDeals > 0) {
                conversionRate = (successDeals * 100.0) / totalDeals;
            }

            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText("📊 СТАТИСТИКА\n\n" +
                    "👥 Всего клиентов: " + totalClients + "\n" +
                    "📋 Всего сделок: " + totalDeals + "\n" +
                    "✅ Совершенных сделок: " + successDeals + "\n" +
                    "❌ Отказов: " + refuseDeals + "\n\n" +
                    "📈 Конверсия: " +
                    (totalDeals > 0 ? String.format("%.1f", conversionRate) : "0") + "%");

            InlineKeyboardButton successListBtn = InlineKeyboardButton.builder()
                    .text("✅ Список совершенных сделок")
                    .callbackData("список сделок")
                    .build();

            InlineKeyboardButton refuseListBtn = InlineKeyboardButton.builder()
                    .text("❌ Список отказанных сделок")
                    .callbackData("список отказов")
                    .build();

            InlineKeyboardButton backBtn = InlineKeyboardButton.builder()
                    .text("🔙 Назад")
                    .callbackData("назад к спискам")
                    .build();

            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(List.of(successListBtn))
                    .keyboardRow(List.of(refuseListBtn))
                    .keyboardRow(List.of(backBtn))
                    .build();

            message.setReplyMarkup(keyboard);
            execute(message);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Отправить файл с завершенными сделками
    private void sendCompletedDealsFile(Long chatId, String resultType) {
        initializeDatabase();

        try {
            String fileName = resultType.equals("совершена") ? "success_deals.txt" : "refused_deals.txt";
            String title = resultType.equals("совершена") ? "СОВЕРШЕННЫЕ СДЕЛКИ" : "ОТКАЗАННЫЕ СДЕЛКИ";

            File file = new File(fileName);
            FileWriter writer = new FileWriter(file);

            String sql = "SELECT name, phone, city, property_type, deal_date FROM deals WHERE result = ? ORDER BY deal_date DESC";

            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, resultType);
                ResultSet rs = pstmt.executeQuery();

                writer.write(title + "\n");
                writer.write("=".repeat(50) + "\n\n");

                int count = 1;
                while (rs.next()) {
                    writer.write(count + ". Имя: " + rs.getString("name") + "\n");
                    writer.write("   Телефон: " + rs.getString("phone") + "\n");
                    writer.write("   Город: " + rs.getString("city") + "\n");
                    writer.write("   Тип недвижимости: " + rs.getString("property_type") + "\n");
                    writer.write("   Дата сделки: " + rs.getTimestamp("deal_date").toLocalDateTime().format(formatter) + "\n");
                    writer.write("-".repeat(30) + "\n");
                    count++;
                }

                writer.write("\nВсего сделок: " + (count - 1));
            }

            writer.close();

            SendDocument document = new SendDocument();
            document.setChatId(chatId.toString());
            document.setDocument(new InputFile(file));
            document.setCaption(resultType.equals("совершена") ? "✅ Список совершенных сделок" : "❌ Список отказанных сделок");

            execute(document);

            file.delete();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getBotUsername() {
        return "@miha_investment_tg_bot";
    }

    @Override
    public String getBotToken() {
        return "8358157348:AAE67B5tKuNXsVgPedH3wPvzB84baYontOw";
    }
}