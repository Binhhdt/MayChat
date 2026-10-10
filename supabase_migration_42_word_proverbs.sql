-- =====================================================================
-- MayChat 0.40.0 — "Nhìn hình đoán chữ": thành ngữ and tục ngữ
--  * The 116 emoji puzzles of migration 39 are put away (active = false,
--    kept in the table). 107 thành ngữ / tục ngữ take their place (emoji
--    pictures for now; a drawn picture can be set later in the img column).
--  * Puzzles 1-4 of the day are easy, 5-7 medium, 8-10 hard (level by
--    the number of letters: up to 12, up to 18, more).
--  * After a puzzle the player sees what the saying means.
-- Run once in Supabase → SQL Editor (after migration 41). Safe to run again.
-- =====================================================================

alter table public.farm_words add column if not exists mean text;   -- what the saying means
alter table public.farm_words add column if not exists img  text;   -- a drawn picture (later); null = the emoji

-- 1. the emoji puzzles of migration 39 are put away
update public.farm_words set active = false where id between 1 and 116;

-- 2. thành ngữ and tục ngữ
insert into public.farm_words (id, level, pics, answer, shown, mean) values
    (1001, 2, '🐘 ➕ 🐭', 'DAU VOI DUOI CHUOT', 'Đầu voi đuôi chuột', 'Bắt đầu thì to tát, kết thúc thì nhỏ nhặt, không ra gì.'),
    (1002, 1, '💧 🦆', 'NUOC DO DAU VIT', 'Nước đổ đầu vịt', 'Nói mãi mà người nghe không tiếp thu gì.'),
    (1003, 2, '🐸 🕳️', 'ECH NGOI DAY GIENG', 'Ếch ngồi đáy giếng', 'Hiểu biết hạn hẹp mà tưởng mình biết nhiều.'),
    (1004, 2, '🎻 👂 🐃', 'DAN GAY TAI TRAU', 'Đàn gảy tai trâu', 'Nói điều hay với người không hiểu, uổng công.'),
    (1005, 2, '🏇 👀 🌸', 'CUOI NGUA XEM HOA', 'Cưỡi ngựa xem hoa', 'Làm việc qua loa, xem vội vàng không kỹ.'),
    (1006, 1, '🔍 📍 🌊', 'MO KIM DAY BIEN', 'Mò kim đáy biển', 'Tìm một thứ gần như không thể tìm thấy.'),
    (1007, 2, '💧 ⛰️ ⏳', 'NUOC CHAY DA MON', 'Nước chảy đá mòn', 'Kiên trì bền bỉ thì việc khó cũng thành.'),
    (1008, 2, '👴 ↩️ 💥', 'GAY ONG DAP LUNG ONG', 'Gậy ông đập lưng ông', 'Hại người lại hóa hại mình.'),
    (1009, 2, '🎨 🐍 ➕ 🦵', 'VE RAN THEM CHAN', 'Vẽ rắn thêm chân', 'Làm thừa, làm hỏng việc vốn đã ổn.'),
    (1010, 1, '🔓 🐯 🌳', 'THA HO VE RUNG', 'Thả hổ về rừng', 'Để kẻ nguy hiểm thoát, sau này mang họa.'),
    (1011, 1, '🍚 🥣 🦶 🍽️', 'AN CHAO DA BAT', 'Ăn cháo đá bát', 'Vô ơn với người đã giúp mình.'),
    (1012, 1, '🐝 👕', 'NUOI ONG TAY AO', 'Nuôi ong tay áo', 'Nuôi dưỡng kẻ sau này hại mình.'),
    (1013, 2, '🌳 😮 ⏳', 'HA MIENG CHO SUNG', 'Há miệng chờ sung', 'Lười biếng, chỉ trông chờ may mắn.'),
    (1014, 1, '🤗 🌳 ⏳ 🐇', 'OM CAY DOI THO', 'Ôm cây đợi thỏ', 'Chờ may mắn lặp lại một cách viển vông.'),
    (1015, 2, '🪓 🛣️ 🗣️', 'DEO CAY GIUA DUONG', 'Đẽo cày giữa đường', 'Không có chủ kiến, nghe ai cũng làm theo.'),
    (1016, 2, '🐓 🐥 🐥', 'GA TRONG NUOI CON', 'Gà trống nuôi con', 'Người cha một mình nuôi con.'),
    (1017, 2, '🐟 🏺 🐦 🔒', 'CA CHAU CHIM LONG', 'Cá chậu chim lồng', 'Bị gò bó, mất tự do.'),
    (1018, 1, '🐟 💧 😄', 'NHU CA GAP NUOC', 'Như cá gặp nước', 'Gặp được hoàn cảnh rất thuận lợi.'),
    (1019, 2, '🌦️ 🌬️ 🌾', 'MUA THUAN GIO HOA', 'Mưa thuận gió hòa', 'Thời tiết thuận lợi, mùa màng tốt.'),
    (1020, 2, '⬆️ 🐘 ⬇️ 🐕', 'LEN VOI XUONG CHO', 'Lên voi xuống chó', 'Lúc sang lúc hèn, thay đổi thất thường.'),
    (1021, 2, '🤰 ⭕ 👶', 'ME TRON CON VUONG', 'Mẹ tròn con vuông', 'Sinh nở bình an, mọi việc trọn vẹn.'),
    (1022, 2, '🦶 💪 ⛰️', 'CHAN CUNG DA MEM', 'Chân cứng đá mềm', 'Lời chúc đi đường xa bình an, vượt mọi khó khăn.'),
    (1023, 2, '1️⃣ 💪 2️⃣ ✅', 'MOT CONG DOI VIEC', 'Một công đôi việc', 'Làm một lần mà được hai việc.'),
    (1024, 2, '3️⃣ 🦵 4️⃣ 🏃', 'BA CHAN BON CANG', 'Ba chân bốn cẳng', 'Chạy rất vội vàng.'),
    (1025, 2, '👖 🤏 💸', 'THAT LUNG BUOC BUNG', 'Thắt lưng buộc bụng', 'Tiết kiệm, chi tiêu dè sẻn.'),
    (1026, 2, '👂 🧱 🌲', 'TAI VACH MACH RUNG', 'Tai vách mạch rừng', 'Nói gì cũng có thể bị người khác nghe được.'),
    (1027, 2, '🌲 🥇 🌊 🥈', 'RUNG VANG BIEN BAC', 'Rừng vàng biển bạc', 'Đất nước giàu tài nguyên.'),
    (1028, 1, '🐌 🗣️ ❓', 'AN OC NOI MO', 'Ăn ốc nói mò', 'Nói không có căn cứ.'),
    (1029, 1, '🦗 🦶 🚗', 'CHAU CHAU DA XE', 'Châu chấu đá xe', 'Sức yếu mà dám chống lại kẻ mạnh hơn nhiều.'),
    (1030, 1, '🐟 🐟 🐟 1️⃣', 'CA ME MOT LUA', 'Cá mè một lứa', 'Những người cùng một giuộc, như nhau.'),
    (1031, 2, '🐭 ⬇️ 🍚', 'CHUOT SA CHINH GAO', 'Chuột sa chĩnh gạo', 'Gặp được chỗ sung sướng bất ngờ.'),
    (1032, 1, '🥓 👄 🐱', 'MO DE MIENG MEO', 'Mỡ để miệng mèo', 'Để thứ quý ngay chỗ dễ mất.'),
    (1033, 1, '🤚 🥌 🙈', 'NEM DA GIAU TAY', 'Ném đá giấu tay', 'Hại người một cách lén lút.'),
    (1034, 2, '🥁 🏃 🥢', 'DANH TRONG BO DUI', 'Đánh trống bỏ dùi', 'Làm dở dang rồi bỏ.'),
    (1035, 2, '🐱 ➡️ 🕳️ ✅', 'DAU XUOI DUOI LOT', 'Đầu xuôi đuôi lọt', 'Khởi đầu suôn sẻ thì cuối cùng cũng xong.'),
    (1036, 2, '🐘 ➕ 🧚', 'DUOC VOI DOI TIEN', 'Được voi đòi tiên', 'Tham lam, được cái này đòi cái khác.'),
    (1037, 1, '🆕 😍 🗑️', 'CO MOI NOI CU', 'Có mới nới cũ', 'Có cái mới thì bỏ rơi cái cũ.'),
    (1038, 2, '🐕 🍖 🐱 🍲', 'CHO TREO MEO DAY', 'Chó treo mèo đậy', 'Cất giữ đồ ăn cẩn thận.'),
    (1039, 2, '🍲 🍲 🤝', 'NOI NAO UP VUNG NAY', 'Nồi nào úp vung nấy', 'Ai cũng có người hợp với mình.'),
    (1040, 2, '🍯 🦟 💀', 'MAT NGOT CHET RUOI', 'Mật ngọt chết ruồi', 'Lời ngọt ngào dễ làm người ta mắc bẫy.'),
    (1041, 2, '☀️ 🌫️ 👨‍🌾', 'MOT NANG HAI SUONG', 'Một nắng hai sương', 'Lao động vất vả.'),
    (1042, 2, '9️⃣ 👥 🔟 💭', 'CHIN NGUOI MUOI Y', 'Chín người mười ý', 'Mỗi người một ý, khó thống nhất.'),
    (1043, 2, '🏚️ 🥬 💸', 'NGHEO ROT MONG TOI', 'Nghèo rớt mồng tơi', 'Rất nghèo.'),
    (1044, 2, '🌧️ 🐭 💦', 'UOT NHU CHUOT LOT', 'Ướt như chuột lột', 'Ướt sũng.'),
    (1045, 1, '🐰 😨', 'NHAT NHU THO DE', 'Nhát như thỏ đế', 'Rất nhút nhát.'),
    (1046, 1, '🐢 ⏳', 'CHAM NHU RUA', 'Chậm như rùa', 'Rất chậm chạp.'),
    (1047, 1, '🐘 💪', 'KHOE NHU VOI', 'Khỏe như voi', 'Rất khỏe.'),
    (1048, 1, '🐿️ 💨', 'NHANH NHU SOC', 'Nhanh như sóc', 'Rất nhanh nhẹn.'),
    (1049, 1, '👥 👥 🐜 🐜', 'DONG NHU KIEN', 'Đông như kiến', 'Rất đông người.'),
    (1050, 1, '🌿 💰 📉', 'RE NHU BEO', 'Rẻ như bèo', 'Rất rẻ.'),
    (1051, 1, '🐚 🤐', 'CAM NHU HEN', 'Câm như hến', 'Im lặng không nói gì.'),
    (1052, 1, '😄 🧧 🎆', 'VUI NHU TET', 'Vui như Tết', 'Rất vui.'),
    (1053, 2, '💧 🔥', 'NUOC SOI LUA BONG', 'Nước sôi lửa bỏng', 'Hoàn cảnh rất gấp gáp, nguy hiểm.'),
    (1054, 2, '⛵ 🌬️', 'THUAN BUOM XUOI GIO', 'Thuận buồm xuôi gió', 'Mọi việc suôn sẻ.'),
    (1055, 2, '🧙‍♂️ 👀 🐘', 'THAY BOI XEM VOI', 'Thầy bói xem voi', 'Chỉ thấy một phần mà tưởng biết hết.'),
    (1056, 2, '🐋 🐟', 'CA LON NUOT CA BE', 'Cá lớn nuốt cá bé', 'Kẻ mạnh ăn hiếp kẻ yếu.'),
    (1057, 2, '🔦 🏃 🚗', 'CAM DEN CHAY TRUOC O TO', 'Cầm đèn chạy trước ô tô', 'Làm việc vội vàng, đi trước khi chưa đến lúc.'),
    (1058, 2, '🥢 🦠 🍽️ ✨', 'DUA MOC CHOI MAM SON', 'Đũa mốc chòi mâm son', 'Thân phận thấp mà muốn trèo cao.'),
    (1059, 2, '🐍 🐔 🏠', 'CONG RAN CAN GA NHA', 'Cõng rắn cắn gà nhà', 'Đưa kẻ xấu về hại người nhà.'),
    (1060, 2, '🧶 👋 🌳 🌳', 'RUT DAY DONG RUNG', 'Rút dây động rừng', 'Đụng tới một việc thì ảnh hưởng nhiều việc khác.'),
    (1061, 1, '🍚 👕 💪', 'AN CHAC MAC BEN', 'Ăn chắc mặc bền', 'Chọn thứ thiết thực, lâu bền.'),
    (1062, 2, '🏞️ ➡️ 🥇', 'TAC DAT TAC VANG', 'Tấc đất tấc vàng', 'Đất đai rất quý.'),
    (1063, 2, '🌧️ ⏳ 🌱', 'MUA DAM THAM LAU', 'Mưa dầm thấm lâu', 'Dạy bảo từ từ thì thấm sâu.'),
    (1064, 2, '🍃 🤗 🍂', 'LA LANH DUM LA RACH', 'Lá lành đùm lá rách', 'Người khá giúp người khó.'),
    (1065, 2, '🎋 👴 🌱', 'TRE GIA MANG MOC', 'Tre già măng mọc', 'Thế hệ trước qua đi, thế hệ sau nối tiếp.'),
    (1066, 2, '🥤 🧠 ⛰️ 💧', 'UONG NUOC NHO NGUON', 'Uống nước nhớ nguồn', 'Nhớ ơn người đi trước.'),
    (1067, 2, '🍎 🧠 👨‍🌾 🌳', 'AN QUA NHO KE TRONG CAY', 'Ăn quả nhớ kẻ trồng cây', 'Hưởng thành quả thì nhớ ơn người làm ra.'),
    (1068, 3, '⚙️ 🔧 ⏳ 📍', 'CO CONG MAI SAT CO NGAY NEN KIM', 'Có công mài sắt có ngày nên kim', 'Kiên trì thì việc gì cũng thành.'),
    (1069, 3, '1️⃣ 🌳 ❌ ⛰️', 'MOT CAY LAM CHANG NEN NON', 'Một cây làm chẳng nên non', 'Một mình thì khó làm nên việc lớn.'),
    (1070, 3, '🌳 🌳 🌳 ⛰️', 'BA CAY CHUM LAI NEN HON NUI CAO', 'Ba cây chụm lại nên hòn núi cao', 'Đoàn kết thì sức mạnh lớn.'),
    (1071, 3, '🖋️ ⚫ 💡 ✨', 'GAN MUC THI DEN GAN DEN THI SANG', 'Gần mực thì đen gần đèn thì sáng', 'Bạn bè, môi trường ảnh hưởng tới mình.'),
    (1072, 2, '🌳 👍 🎨 👎', 'TOT GO HON TOT NUOC SON', 'Tốt gỗ hơn tốt nước sơn', 'Phẩm chất bên trong quý hơn vẻ ngoài.'),
    (1073, 3, '🍽️ 🧼 👕 🌸', 'DOI CHO SACH RACH CHO THOM', 'Đói cho sạch rách cho thơm', 'Nghèo vẫn giữ trong sạch.'),
    (1074, 1, '👧 🤕 👧 🤝', 'CHI NGA EM NANG', 'Chị ngã em nâng', 'Anh chị em thương yêu giúp đỡ nhau.'),
    (1075, 2, '👦 👦 ✋ 🦶', 'ANH EM NHU THE TAY CHAN', 'Anh em như thể tay chân', 'Anh em ruột thịt gắn bó.'),
    (1076, 3, '👦 📈 👨 🏠 🍀', 'CON HON CHA LA NHA CO PHUC', 'Con hơn cha là nhà có phúc', 'Con giỏi hơn cha mẹ là điều đáng mừng.'),
    (1077, 3, '👨‍🏫 📚 👫', 'HOC THAY KHONG TAY HOC BAN', 'Học thầy không tày học bạn', 'Học từ bạn bè cũng rất quý.'),
    (1078, 3, '👨‍🏫 ❌ 🤷', 'KHONG THAY DO MAY LAM NEN', 'Không thầy đố mày làm nên', 'Phải có thầy dạy mới thành công.'),
    (1079, 2, '🙇 ➡️ 📖', 'TIEN HOC LE HAU HOC VAN', 'Tiên học lễ hậu học văn', 'Học lễ phép trước, học kiến thức sau.'),
    (1080, 3, '🗣️ 💬 💰 ❌', 'LOI NOI CHANG MAT TIEN MUA', 'Lời nói chẳng mất tiền mua', 'Hãy nói lời hay, ý đẹp.'),
    (1081, 1, '😇 🍀', 'O HIEN GAP LANH', 'Ở hiền gặp lành', 'Sống tốt sẽ gặp điều tốt.'),
    (1082, 2, '🌬️ ➡️ 🌪️', 'GIEO GIO GAT BAO', 'Gieo gió gặt bão', 'Làm điều xấu sẽ nhận hậu quả lớn.'),
    (1083, 1, '🤑 🤕', 'THAM THI THAM', 'Tham thì thâm', 'Tham lam sẽ chịu thiệt.'),
    (1084, 3, '💧 🔭 🔥 🏠', 'NUOC XA KHONG CUU DUOC LUA GAN', 'Nước xa không cứu được lửa gần', 'Thứ ở xa không giúp được lúc gấp.'),
    (1085, 3, '👨‍👩‍👦 ✈️ 🏡 🤝 🏡', 'BAN ANH EM XA MUA LANG GIENG GAN', 'Bán anh em xa mua láng giềng gần', 'Hàng xóm gần gũi giúp nhau kịp thời.'),
    (1086, 3, '🐴 🤒 🐴 🐴 🌾 ❌', 'MOT CON NGUA DAU CA TAU BO CO', 'Một con ngựa đau cả tàu bỏ cỏ', 'Đồng cảm, thương nhau khi một người gặp nạn.'),
    (1087, 3, '🐓 😠 🐓 📢', 'CON GA TUC NHAU TIENG GAY', 'Con gà tức nhau tiếng gáy', 'Ganh đua nhau, không chịu thua.'),
    (1088, 3, '🦋 ⬇️ 🌧️', 'CHUON CHUON BAY THAP THI MUA', 'Chuồn chuồn bay thấp thì mưa', 'Kinh nghiệm xem thời tiết.'),
    (1089, 3, '🐜 🌾 ⏳ 🏠', 'KIEN THA LAU CUNG DAY TO', 'Kiến tha lâu cũng đầy tổ', 'Góp nhặt từng chút sẽ thành nhiều.'),
    (1090, 2, '📏 ❌ ➡️ 🛣️', 'SAI MOT LY DI MOT DAM', 'Sai một ly đi một dặm', 'Sai một chút lúc đầu thành sai rất nhiều về sau.'),
    (1091, 3, '🙋 ⛰️ 👀 🏔️', 'DUNG NUI NAY TRONG NUI NO', 'Đứng núi này trông núi nọ', 'Không bằng lòng với cái mình có.'),
    (1092, 3, '🦀 🏖️ 🌊', 'DA TRANG XE CAT BIEN DONG', 'Dã tràng xe cát biển Đông', 'Làm việc vất vả mà uổng công.'),
    (1093, 2, '🍎 🌳 🚧', 'AN CAY NAO RAO CAY NAY', 'Ăn cây nào rào cây nấy', 'Được lợi từ đâu thì bảo vệ nơi đó.'),
    (1094, 2, '🐴 🛤️ 🔁', 'NGUA QUEN DUONG CU', 'Ngựa quen đường cũ', 'Quen thói cũ, khó bỏ.'),
    (1095, 3, '🐛 👟 😤', 'CON GIUN XEO LAM CUNG QUAN', 'Con giun xéo lắm cũng quằn', 'Bị ép quá thì người hiền cũng phản kháng.'),
    (1096, 2, '💧 👐 🏠', 'NUOC LA MA VA NEN HO', 'Nước lã mà vã nên hồ', 'Tay trắng làm nên sự nghiệp.'),
    (1097, 2, '🥚 🧠 🦆', 'TRUNG KHON HON VIT', 'Trứng khôn hơn vịt', 'Con nhỏ mà muốn dạy người lớn.'),
    (1098, 3, '🐃 ⏳ 💧 🌫️', 'TRAU CHAM UONG NUOC DUC', 'Trâu chậm uống nước đục', 'Chậm chân thì chịu phần kém.'),
    (1099, 2, '🐔 🤕 🔄', 'GA QUE AN QUAN COI XAY', 'Gà què ăn quẩn cối xay', 'Chỉ quanh quẩn một chỗ, không dám đi xa.'),
    (1100, 3, '🍱 🏃 🌊 🐢', 'AN CO DI TRUOC LOI NUOC THEO SAU', 'Ăn cỗ đi trước lội nước theo sau', 'Hưởng thì tranh trước, khó thì lùi sau.'),
    (1101, 3, '🐕 🗣️ 🦷 ❌', 'CHO SUA LA CHO KHONG CAN', 'Chó sủa là chó không cắn', 'Người hay to tiếng thường không làm hại ai.'),
    (1102, 2, '✋ 🌱 🌳', 'UON CAY TU THUO CON NON', 'Uốn cây từ thuở còn non', 'Dạy con từ khi còn nhỏ.'),
    (1103, 3, '🔥 🥇 🧗 💪', 'LUA THU VANG GIAN NAN THU SUC', 'Lửa thử vàng gian nan thử sức', 'Khó khăn giúp biết sức mạnh thật.'),
    (1104, 3, '❤️ 👥 🤗', 'THUONG NGUOI NHU THE THUONG THAN', 'Thương người như thể thương thân', 'Yêu thương người khác như bản thân.'),
    (1105, 1, '🧗 ⛰️ 🚩', 'CO CHI THI NEN', 'Có chí thì nên', 'Có ý chí thì sẽ thành công.'),
    (1106, 3, '🦷 💇 👤', 'CAI RANG CAI TOC LA GOC CON NGUOI', 'Cái răng cái tóc là góc con người', 'Răng, tóc thể hiện vẻ đẹp con người.'),
    (1107, 3, '🐄 🏃 🔨 🏠', 'MAT BO MOI LO LAM CHUONG', 'Mất bò mới lo làm chuồng', 'Xảy ra chuyện rồi mới lo phòng tránh.')
on conflict (id) do nothing;
do $x$ begin perform setval(pg_get_serial_sequence('public.farm_words', 'id'), greatest((select max(id) from public.farm_words), 1)); end $x$;

-- 3. the next puzzle: by level for its place in the day, at random, no repeat
create or replace function public.farm_word_pick(f public.farms)
returns public.farms
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_today date := public.farm_today();
    v_n int := case when f.word_day = v_today then f.word_n else 0 end;
    -- puzzles 1-4 of the day are easy, 5-7 medium, 8-10 hard
    v_lv int := case when v_n < 4 then 1 when v_n < 7 then 2 else 3 end;
    v_id int;
    v_seen int[] := coalesce(f.word_seen, '{}');
begin
    select id into v_id from public.farm_words
     where active and level = v_lv and not (id = any(v_seen)) order by random() limit 1;
    if v_id is null then
        -- every puzzle of this level has been had: a new round for this level
        v_seen := array(select s from unnest(v_seen) s
                         where s not in (select id from public.farm_words where level = v_lv));
        select id into v_id from public.farm_words
         where active and level = v_lv and id is distinct from f.word_last order by random() limit 1;
    end if;
    if v_id is null then   -- no puzzle of that level at all: any puzzle
        select id into v_id from public.farm_words where active order by random() limit 1;
    end if;
    update public.farms set word_cur = v_id, word_seen = v_seen, word_at = null, word_open = '{}', word_cut = false
     where user_id = f.user_id returning * into f;
    return f;
end;
$$;

create or replace function public.farm_word_view(f public.farms)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
    v_today date := public.farm_today();
    v_n int := case when f.word_day = v_today then f.word_n else 0 end;
    w public.farm_words;
    v_ans text;
    v_len int;
    v_tiles text[] := '{}';
    v_abc text := 'ABCDEGHIKLMNOPQRSTUVXY';
    v_extra int;
    v_letters jsonb;
    v_open jsonb;
    i int;
begin
    select * into w from public.farm_words where active and id = f.word_cur;
    if not found then
        -- no puzzle chosen yet (the next one is drawn when the player asks for it)
        return jsonb_build_object('n', v_n, 'max', 10,
            'finished', not exists (select 1 from public.farm_words where active), 'waiting', true);
    end if;
    v_ans := replace(w.answer, ' ', '');
    v_len := char_length(v_ans);
    -- the letters of the answer, then some extra letters (always the same for a puzzle)
    for i in 1 .. v_len loop v_tiles := v_tiles || substr(v_ans, i, 1); end loop;
    v_extra := greatest(4, 14 - v_len);
    for i in 1 .. v_extra loop
        v_tiles := v_tiles || substr(v_abc, 1 + ('x' || substr(md5(w.id || ':e:' || i), 1, 6))::bit(24)::int % char_length(v_abc), 1);
    end loop;
    select jsonb_agg(t.l order by md5(w.id || ':s:' || t.k)) into v_letters
      from unnest(v_tiles) with ordinality as t(l, k)
     where not f.word_cut or t.k <= v_len;
    select coalesce(jsonb_agg(jsonb_build_object('i', o, 'c', substr(v_ans, o + 1, 1)) order by o), '[]'::jsonb) into v_open
      from unnest(f.word_open) as o;
    return jsonb_build_object(
        'n', v_n, 'max', 10, 'finished', false,
        'id', w.id, 'no', v_n + 1, 'level', w.level, 'pics', w.pics, 'img', w.img,
        'words', (select jsonb_agg(char_length(x) order by k) from unnest(string_to_array(w.answer, ' ')) with ordinality as s(x, k)),
        'letters', v_letters, 'open', v_open, 'cut', f.word_cut,
        'costs', jsonb_build_object('letter', 20, 'cut', 30, 'skip', 50),
        'limit', 60,
        'left_ms', case when f.word_at is null then 60000
                        else greatest(0, 60000 - floor(extract(epoch from (now() - f.word_at)) * 1000)::int) end
    );
end;
$$;

create or replace function public.farm_word_get()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_today date := public.farm_today();
    f public.farms;
    w public.farm_words;
    v_n int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    v_n := case when f.word_day = v_today then f.word_n else 0 end;
    if v_n < 10 then
        select * into w from public.farm_words where active and id = f.word_cur;
        if found and f.word_at is not null and now() >= f.word_at + interval '60 seconds' then
            -- time ran out (the game was closed, or the minute passed): this puzzle is lost
            update public.farms
               set word_last = w.id, word_seen = word_seen || w.id, word_cur = null, word_day = v_today, word_n = v_n + 1, word_open = '{}', word_cut = false, word_at = null, updated_at = now()
             where user_id = v_me returning * into f;
            return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f),
                'result', jsonb_build_object('timeout', true, 'shown', w.shown, 'pics', w.pics, 'img', w.img, 'mean', w.mean));
        end if;
        if not found then f := public.farm_word_pick(f); end if;
        if f.word_cur is not null and f.word_at is null then
            -- the minute of this puzzle starts now
            update public.farms set word_at = now() where user_id = v_me returning * into f;
        end if;
    end if;
    return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f));
end;
$$;

create or replace function public.farm_word_answer(p_letters text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_today date := public.farm_today();
    f public.farms;
    w public.farm_words;
    v_n int;
    v_mat text;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    v_n := case when f.word_day = v_today then f.word_n else 0 end;
    if v_n >= 10 then raise exception 'word_day_done'; end if;
    select * into w from public.farm_words where active and id = f.word_cur;
    if not found or f.word_at is null then raise exception 'word_not_started'; end if;
    if now() > f.word_at + interval '62 seconds' then
        -- too late (2 seconds kept for a slow network): this puzzle is lost
        update public.farms
           set word_last = w.id, word_seen = word_seen || w.id, word_cur = null, word_day = v_today, word_n = v_n + 1, word_open = '{}', word_cut = false,
               word_at = null, updated_at = now()
         where user_id = v_me returning * into f;
        return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f),
            'result', jsonb_build_object('ok', false, 'timeout', true, 'shown', w.shown, 'pics', w.pics, 'img', w.img, 'mean', w.mean));
    end if;

    if regexp_replace(upper(coalesce(p_letters, '')), '[^A-Z]', '', 'g') <> replace(w.answer, ' ', '') then
        return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f), 'result', jsonb_build_object('ok', false));
    end if;

    -- right: 10 gold, and 1 time in 10 a material
    if random() < 0.1 then v_mat := (array['go', 'ngoi', 'gach'])[1 + floor(random() * 3)::int]; end if;
    f := public.farm_roll(f);
    f.week_gold := f.week_gold + 10;
    perform public.farm_save_counters(f);
    update public.farms
       set gold = gold + 10,
           mats = case when v_mat is null then mats else public.farm_mat_add(mats, v_mat, 1) end,
           word_last = w.id, word_seen = word_seen || w.id, word_cur = null, word_day = v_today, word_n = v_n + 1, word_open = '{}', word_cut = false,
           word_at = null, updated_at = now()
     where user_id = v_me
    returning * into f;
    return public.farm_state(f) || jsonb_build_object(
        'word', public.farm_word_view(f),
        'result', jsonb_build_object('ok', true, 'shown', w.shown, 'gold', 10, 'mat', v_mat, 'img', w.img, 'mean', w.mean)
    );
end;
$$;

create or replace function public.farm_word_hint(p_kind text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_me uuid := auth.uid();
    v_today date := public.farm_today();
    f public.farms;
    w public.farm_words;
    v_n int;
    v_cost int;
    v_len int;
    v_next int;
begin
    if v_me is null then raise exception 'not_authenticated'; end if;
    v_cost := case p_kind when 'letter' then 20 when 'cut' then 30 when 'skip' then 50 else null end;
    if v_cost is null then raise exception 'bad_hint'; end if;
    select * into f from public.farms where user_id = v_me for update;
    if not found then raise exception 'no_farm'; end if;
    v_n := case when f.word_day = v_today then f.word_n else 0 end;
    if v_n >= 10 then raise exception 'word_day_done'; end if;
    select * into w from public.farm_words where active and id = f.word_cur;
    if not found then raise exception 'word_not_started'; end if;
    if f.word_at is null or now() > f.word_at + interval '62 seconds' then raise exception 'word_timeout'; end if;
    if f.gold < v_cost then raise exception 'not_enough_gold'; end if;
    v_len := char_length(replace(w.answer, ' ', ''));

    if p_kind = 'letter' then
        select min(i) into v_next from generate_series(0, v_len - 1) i where not (i = any(f.word_open));
        if v_next is null or cardinality(f.word_open) >= v_len - 1 then raise exception 'no_more_hint'; end if;
        update public.farms set gold = gold - v_cost, word_open = word_open || v_next, updated_at = now()
         where user_id = v_me returning * into f;
    elsif p_kind = 'cut' then
        if f.word_cut then raise exception 'no_more_hint'; end if;
        update public.farms set gold = gold - v_cost, word_cut = true, updated_at = now()
         where user_id = v_me returning * into f;
    else
        update public.farms
           set gold = gold - v_cost, word_last = w.id, word_seen = word_seen || w.id, word_cur = null, word_day = v_today, word_n = v_n + 1, word_open = '{}', word_cut = false, word_at = null, updated_at = now()
         where user_id = v_me returning * into f;
        return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f),
            'result', jsonb_build_object('skipped', true, 'shown', w.shown, 'img', w.img, 'mean', w.mean));
    end if;
    return public.farm_state(f) || jsonb_build_object('word', public.farm_word_view(f));
end;
$$;

revoke all on function public.farm_word_pick(public.farms) from public, anon, authenticated;
