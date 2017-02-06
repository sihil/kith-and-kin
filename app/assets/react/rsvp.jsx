function Button(props) {
    const classes = "button is-large "+props.colour;
    return (
        <button className={classes} value={props.value} onClick={() => props.onClick()}>{props.text}</button>
    )
}

class Question extends React.Component {
    render() {
        const question = this.props.question.question;
        const answers = this.props.question.answers;
        const answerButtons = answers.map((answer, index) => {
            const colour = (this.props.answer === answer) ? "is-success" : "";
            return (
                <Button key={index} colour={colour} text={answer.text} onClick={() => this.props.onAnswer(answer)}/>
            );
        });
        return (
            <div className="question">
                <h2 className="heading">{question}</h2>
                {answerButtons}
            </div>
        )
    }
}

class Rsvp extends React.Component {
    constructor() {
        super();
        this.state = {
            questions: null,
            startKey: null,
            answers: {}
        }
    }
    findAnswer(questions, questionKey, answerKey) {
        const question = questions[questionKey];
        return question.answers.find((answer) => { return answer.updateKey === answerKey });
    }
    componentDidMount() {
        // get questions
        fetch("/rsvp/questions", {
            credentials: 'include'
        }).then((response) => {

            return response.json().then((json) => {
                const answerMap = json.answers;
                const answerMapWithObjects = Object.assign(...Object.keys(answerMap).map(k => ({[k]: this.findAnswer(json.questions, k, answerMap[k])})));
                this.setState({questions: json.questions, startKey: json.startPage, answers: answerMapWithObjects});
            });
        });
        // get any responses
    }
    onAnswer(question, answer) {
        fetch("/rsvp/update", {
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'Csrf-Token': window.csrf
            },
            method: 'POST',
            body: JSON.stringify({field: question.updateKey, value: answer.updateKey})
        }).then((res) => {
            if (res.status == 200) {
                this.setState((previous) => {
                    const newAnswer = {};
                    newAnswer[question.updateKey] = answer;
                    return {answers: Object.assign({}, previous.answers, newAnswer)};
                });
            }
        });
    }
    questionAnswers(key) {
        const question = this.state.questions[key];
        if (question) {
            const answer = this.state.answers[question.updateKey];
            if (answer) {
                return [{question: question, answer: answer}].concat(this.questionAnswers(answer.nextQuestion));
            } else {
                return [{question: question}];
            }
        } else return [];
    }
    renderQuestion(question, answer) {
        return (
            <Question key={question.updateKey} question={question} answer={answer} onAnswer={(answer) => this.onAnswer(question, answer)}/>
        );
    }
    render() {
        if (this.state.questions) {
            const questionList = this.questionAnswers(this.state.startKey);
            const questionElements = questionList.map((question) => {
                return this.renderQuestion(question.question, question.answer);
            });
            return (
                <div>{questionElements}</div>
            );
        } else {
            return (
                <p>Loading RSVP information...</p>
            );
        }
    }
}

ReactDOM.render(
    <Rsvp />,
    document.getElementById('container')
);